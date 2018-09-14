package com.justindriggers.example.renderer.swapchain;

import com.justindriggers.example.renderer.device.PhysicalDeviceMetadata;
import com.justindriggers.example.window.Window;
import com.justindriggers.vulkan.devices.logical.LogicalDevice;
import com.justindriggers.vulkan.devices.physical.PhysicalDevice;
import com.justindriggers.vulkan.models.Extent2D;
import com.justindriggers.vulkan.pipeline.CommandBuffer;
import com.justindriggers.vulkan.pipeline.CommandPool;
import com.justindriggers.vulkan.pipeline.GraphicsPipeline;
import com.justindriggers.vulkan.pipeline.PipelineLayout;
import com.justindriggers.vulkan.pipeline.RenderPass;
import com.justindriggers.vulkan.pipeline.commands.BeginRenderPassCommand;
import com.justindriggers.vulkan.pipeline.commands.BindPipelineCommand;
import com.justindriggers.vulkan.pipeline.commands.DrawCommand;
import com.justindriggers.vulkan.pipeline.commands.EndRenderPassCommand;
import com.justindriggers.vulkan.pipeline.shader.ShaderModule;
import com.justindriggers.vulkan.queue.QueueFamily;
import com.justindriggers.vulkan.surface.Surface;
import com.justindriggers.vulkan.surface.models.PresentMode;
import com.justindriggers.vulkan.surface.models.capabilities.SurfaceCapabilities;
import com.justindriggers.vulkan.surface.models.format.ColorFormat;
import com.justindriggers.vulkan.surface.models.format.ColorSpace;
import com.justindriggers.vulkan.surface.models.format.SurfaceFormat;
import com.justindriggers.vulkan.swapchain.Framebuffer;
import com.justindriggers.vulkan.swapchain.ImageView;
import com.justindriggers.vulkan.swapchain.Swapchain;
import com.justindriggers.vulkan.swapchain.models.Image;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SwapchainManagerImpl implements SwapchainManager {

    private static final Logger LOGGER = Logger.getLogger(SwapchainManagerImpl.class.getName());

    private final CommandPool commandPool;

    private SwapchainContainer currentSwapchainContainer;

    public SwapchainManagerImpl(final CommandPool commandPool) {
        this.commandPool = commandPool;
    }

    @Override
    public void refresh(final Window window,
                        final Surface surface,
                        final PhysicalDeviceMetadata physicalDeviceMetadata,
                        final LogicalDevice device,
                        final ShaderModule vertexShader,
                        final ShaderModule fragmentShader) {
        Optional.ofNullable(currentSwapchainContainer)
                .ifPresent(SwapchainContainer::close);

        currentSwapchainContainer = new SwapchainContainer(window, surface, physicalDeviceMetadata, device,
                vertexShader, fragmentShader);
    }

    @Override
    public Swapchain getCurrentSwapchain() {
        return Optional.ofNullable(currentSwapchainContainer)
                .map(SwapchainContainer::getSwapchain)
                .orElseThrow(() -> new IllegalStateException("Swapchain has not been created"));
    }

    @Override
    public List<CommandBuffer> getCurrentCommandBuffers() {
        return Optional.ofNullable(currentSwapchainContainer)
                .map(SwapchainContainer::getCommandBuffers)
                .orElseThrow(() -> new IllegalStateException("Swapchain has not been created"));
    }

    @Override
    public void close() {
        Optional.ofNullable(currentSwapchainContainer)
                .ifPresent(SwapchainContainer::close);
    }

    private class SwapchainContainer implements AutoCloseable {

        private final Swapchain swapchain;
        private final List<ImageView> swapchainImageViews;

        private final RenderPass renderPass;
        private final PipelineLayout pipelineLayout;
        private final GraphicsPipeline graphicsPipeline;

        private final List<Framebuffer> framebuffers;
        private final List<CommandBuffer> commandBuffers;

        SwapchainContainer(final Window window,
                           final Surface surface,
                           final PhysicalDeviceMetadata physicalDeviceMetadata,
                           final LogicalDevice device,
                           final ShaderModule vertexShader,
                           final ShaderModule fragmentShader) {
            final PhysicalDevice physicalDevice = physicalDeviceMetadata.getPhysicalDevice();
            final QueueFamily graphicsQueueFamily = physicalDeviceMetadata.getGraphicsQueueFamily();
            final QueueFamily presentationQueueFamily = physicalDeviceMetadata.getPresentationQueueFamily();

            final SurfaceCapabilities surfaceCapabilities = surface.getCapabilities(physicalDevice);

            final int imageCount = getImageCount(surfaceCapabilities);
            final Extent2D imageExtent = getImageExtent(surfaceCapabilities, window);

            final List<SurfaceFormat> surfaceFormats = surface.getFormats(physicalDevice);
            final SurfaceFormat chosenSurfaceFormat = getBestSurfaceFormat(surfaceFormats);

            final Set<PresentMode> presentModes = surface.getPresentModes(physicalDevice);
            final PresentMode chosenPresentMode = getBestPresentMode(presentModes);

            swapchain = new Swapchain(device, surface, imageCount, chosenSurfaceFormat.getFormat(),
                    chosenSurfaceFormat.getColorSpace(), imageExtent, surfaceCapabilities.getCurrentTransform(),
                    chosenPresentMode, graphicsQueueFamily, presentationQueueFamily);

            final List<Image> swapchainImages = Optional.ofNullable(swapchain.getImages())
                    .orElseGet(Collections::emptyList);

            swapchainImageViews = swapchainImages.stream()
                    .map(image -> new ImageView(device, image, chosenSurfaceFormat.getFormat()))
                    .collect(Collectors.toList());

            renderPass = new RenderPass(device, chosenSurfaceFormat.getFormat());
            pipelineLayout = new PipelineLayout(device);
            graphicsPipeline = new GraphicsPipeline(device, vertexShader, fragmentShader, imageExtent, renderPass,
                    pipelineLayout);

            framebuffers = swapchainImageViews.stream()
                    .map(imageView -> new Framebuffer(device, renderPass, imageView, imageExtent))
                    .collect(Collectors.toList());

            commandBuffers = commandPool.createCommandBuffers(framebuffers.size());

            IntStream.range(0, commandBuffers.size())
                    .forEach(i -> {
                        final CommandBuffer commandBuffer = commandBuffers.get(i);
                        final Framebuffer framebuffer = framebuffers.get(i);

                        commandBuffer.begin();

                        try {
                            commandBuffer.submit(new BeginRenderPassCommand(renderPass, framebuffer, imageExtent));
                            commandBuffer.submit(new BindPipelineCommand(graphicsPipeline));
                            commandBuffer.submit(new DrawCommand(3, 1, 0, 0));
                            commandBuffer.submit(new EndRenderPassCommand());
                        } finally {
                            commandBuffer.end();
                        }
                    });
        }

        @Override
        public void close() {
            Optional.ofNullable(commandBuffers)
                    .ifPresent(commandPool::destroyCommandBuffers);

            Optional.ofNullable(framebuffers)
                    .orElseGet(Collections::emptyList)
                    .forEach(framebuffer -> {
                        try {
                            framebuffer.close();
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Failed to close framebuffer");
                        }
                    });

            Optional.ofNullable(swapchainImageViews)
                    .orElseGet(Collections::emptyList)
                    .forEach(imageView -> {
                        try {
                            imageView.close();
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Failed to close image view");
                        }
                    });

            Stream.of(graphicsPipeline, pipelineLayout, renderPass, swapchain)
                    .filter(Objects::nonNull)
                    .forEachOrdered(closeable -> {
                        try {
                            closeable.close();
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Failed to close " + closeable.getClass().getName());
                        }
                    });
        }

        Swapchain getSwapchain() {
            return swapchain;
        }

        List<CommandBuffer> getCommandBuffers() {
            return commandBuffers;
        }

        private int getImageCount(final SurfaceCapabilities surfaceCapabilities) {
            final int result;

            final int maxImageCount = surfaceCapabilities.getMaxImageCount();
            final int desiredImageCount = surfaceCapabilities.getMinImageCount() + 1;

            if (maxImageCount == 0 || desiredImageCount <= maxImageCount) {
                result = desiredImageCount;
            } else {
                result = maxImageCount;
            }

            return result;
        }

        private Extent2D getImageExtent(final SurfaceCapabilities surfaceCapabilities,
                                               final Window window) {
            final Extent2D result;

            if (surfaceCapabilities.getCurrentExtent().getWidth() != Integer.MAX_VALUE) {
                result = surfaceCapabilities.getCurrentExtent();
            } else {
                final int width = Math.max(
                        surfaceCapabilities.getMinImageExtent().getWidth(),
                        Math.min(
                                surfaceCapabilities.getMaxImageExtent().getWidth(),
                                window.getWidth()
                        )
                );

                final int height = Math.max(
                        surfaceCapabilities.getMinImageExtent().getHeight(),
                        Math.min(
                                surfaceCapabilities.getMaxImageExtent().getHeight(),
                                window.getHeight()
                        )
                );

                result = new Extent2D(width, height);
            }

            return result;
        }

        private SurfaceFormat getBestSurfaceFormat(final List<SurfaceFormat> surfaceFormats) {
            final SurfaceFormat result;

            if (surfaceFormats.isEmpty()) {
                throw new IllegalStateException("Unable to find any supported formats");
            }

            if (surfaceFormats.size() == 1 && surfaceFormats.stream().findFirst()
                    .filter(format -> format.getFormat() == ColorFormat.UNDEFINED).isPresent()) {
                result = new SurfaceFormat(ColorFormat.B8G8R8A8_UNORM, ColorSpace.SRGB_NONLINEAR);
            } else {
                result = surfaceFormats.stream()
                        .filter(surfaceFormat -> ColorFormat.B8G8R8A8_UNORM.equals(surfaceFormat.getFormat())
                                && ColorSpace.SRGB_NONLINEAR.equals(surfaceFormat.getColorSpace()))
                        .findFirst()
                        .orElseGet(() -> surfaceFormats.stream()
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("Unable to find suitable format")));
            }

            return result;
        }

        private PresentMode getBestPresentMode(final Set<PresentMode> presentModes) {
            final PresentMode result;

            if (presentModes.contains(PresentMode.MAILBOX)) {
                result = PresentMode.MAILBOX;
            } else if (presentModes.contains(PresentMode.IMMEDIATE)) {
                result = PresentMode.IMMEDIATE;
            } else if (presentModes.contains(PresentMode.FIFO)) {
                result = PresentMode.FIFO;
            } else {
                result = presentModes.stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Unable to find any supported present modes"));
            }

            return result;
        }
    }
}
