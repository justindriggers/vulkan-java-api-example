package com.justindriggers.example.renderer.swapchain;

import com.justindriggers.example.renderer.device.PhysicalDeviceMetadata;
import com.justindriggers.example.window.Window;
import com.justindriggers.vulkan.command.CommandBuffer;
import com.justindriggers.vulkan.command.CommandPool;
import com.justindriggers.vulkan.command.commands.BeginRenderPassCommand;
import com.justindriggers.vulkan.command.commands.BindPipelineCommand;
import com.justindriggers.vulkan.command.commands.DrawCommand;
import com.justindriggers.vulkan.command.commands.EndRenderPassCommand;
import com.justindriggers.vulkan.command.models.CommandBufferLevel;
import com.justindriggers.vulkan.devices.logical.LogicalDevice;
import com.justindriggers.vulkan.devices.physical.PhysicalDevice;
import com.justindriggers.vulkan.image.Image;
import com.justindriggers.vulkan.image.ImageView;
import com.justindriggers.vulkan.image.models.ImageAspect;
import com.justindriggers.vulkan.image.models.ImageLayout;
import com.justindriggers.vulkan.image.models.ImageViewType;
import com.justindriggers.vulkan.models.Access;
import com.justindriggers.vulkan.models.ColorSpace;
import com.justindriggers.vulkan.models.Extent2D;
import com.justindriggers.vulkan.models.Format;
import com.justindriggers.vulkan.models.Offset2D;
import com.justindriggers.vulkan.models.Rect2D;
import com.justindriggers.vulkan.models.SampleCount;
import com.justindriggers.vulkan.models.clear.ClearColorFloat;
import com.justindriggers.vulkan.models.clear.ClearValue;
import com.justindriggers.vulkan.pipeline.GraphicsPipeline;
import com.justindriggers.vulkan.pipeline.PipelineLayout;
import com.justindriggers.vulkan.pipeline.models.PipelineBindPoint;
import com.justindriggers.vulkan.pipeline.models.PipelineStage;
import com.justindriggers.vulkan.pipeline.models.assembly.InputAssemblyState;
import com.justindriggers.vulkan.pipeline.models.assembly.PrimitiveTopology;
import com.justindriggers.vulkan.pipeline.models.colorblend.BlendFactor;
import com.justindriggers.vulkan.pipeline.models.colorblend.BlendOperation;
import com.justindriggers.vulkan.pipeline.models.colorblend.ColorBlendAttachmentState;
import com.justindriggers.vulkan.pipeline.models.colorblend.ColorBlendState;
import com.justindriggers.vulkan.pipeline.models.colorblend.ColorComponent;
import com.justindriggers.vulkan.pipeline.models.multisample.MultisampleState;
import com.justindriggers.vulkan.pipeline.models.rasterization.CullMode;
import com.justindriggers.vulkan.pipeline.models.rasterization.FrontFace;
import com.justindriggers.vulkan.pipeline.models.rasterization.PolygonMode;
import com.justindriggers.vulkan.pipeline.models.rasterization.RasterizationState;
import com.justindriggers.vulkan.pipeline.models.vertex.VertexInputState;
import com.justindriggers.vulkan.pipeline.models.viewport.Viewport;
import com.justindriggers.vulkan.pipeline.models.viewport.ViewportState;
import com.justindriggers.vulkan.pipeline.shader.ShaderModule;
import com.justindriggers.vulkan.pipeline.shader.ShaderStage;
import com.justindriggers.vulkan.pipeline.shader.ShaderStageType;
import com.justindriggers.vulkan.queue.QueueFamily;
import com.justindriggers.vulkan.surface.Surface;
import com.justindriggers.vulkan.surface.models.PresentMode;
import com.justindriggers.vulkan.surface.models.SurfaceFormat;
import com.justindriggers.vulkan.surface.models.capabilities.SurfaceCapabilities;
import com.justindriggers.vulkan.swapchain.Framebuffer;
import com.justindriggers.vulkan.swapchain.RenderPass;
import com.justindriggers.vulkan.swapchain.Swapchain;
import com.justindriggers.vulkan.swapchain.models.AttachmentLoadOperation;
import com.justindriggers.vulkan.swapchain.models.AttachmentStoreOperation;
import com.justindriggers.vulkan.swapchain.models.ColorAttachment;
import com.justindriggers.vulkan.swapchain.models.Subpass;
import com.justindriggers.vulkan.swapchain.models.SubpassContents;
import com.justindriggers.vulkan.swapchain.models.SubpassDependency;

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
                    .map(image -> new ImageView(device, image, ImageViewType.TWO_DIMENSIONAL,
                            chosenSurfaceFormat.getFormat(), Collections.singleton(ImageAspect.COLOR), 1, 1))
                    .collect(Collectors.toList());

            final List<ColorAttachment> colorAttachments = Collections.singletonList(
                    new ColorAttachment(chosenSurfaceFormat.getFormat(), Collections.singleton(SampleCount.ONE),
                            AttachmentLoadOperation.CLEAR, AttachmentStoreOperation.STORE,
                            AttachmentLoadOperation.DONT_CARE, AttachmentStoreOperation.DONT_CARE,
                            ImageLayout.UNDEFINED, ImageLayout.PRESENT_SRC)
            );

            final List<Subpass> subpasses = Collections.singletonList(
                    new Subpass(PipelineBindPoint.GRAPHICS, colorAttachments, null)
            );

            final List<SubpassDependency> subpassDependencies = Collections.singletonList(
                    new SubpassDependency(-1, 0,
                            Collections.singleton(PipelineStage.COLOR_ATTACHMENT_OUTPUT), Collections.singleton(PipelineStage.COLOR_ATTACHMENT_OUTPUT),
                            Collections.emptySet(), Stream.of(Access.COLOR_ATTACHMENT_READ, Access.COLOR_ATTACHMENT_WRITE).collect(Collectors.toSet()))
            );

            renderPass = new RenderPass(device, subpasses, subpassDependencies);
            pipelineLayout = new PipelineLayout(device, null);

            final List<ShaderStage> stages = Stream.of(
                    new ShaderStage(ShaderStageType.VERTEX, vertexShader, "main"),
                    new ShaderStage(ShaderStageType.FRAGMENT, fragmentShader, "main")
            ).collect(Collectors.toList());

            // Vertices are hardcoded within the vertex shader, so there are no bindings/locations to declare here
            final VertexInputState vertexInputState = VertexInputState.builder().build();
            final InputAssemblyState inputAssemblyState = new InputAssemblyState(PrimitiveTopology.TRIANGLE_LIST,
                    false);

            final List<Viewport> viewports = Collections.singletonList(
                    new Viewport(0, 0, imageExtent.getWidth(), imageExtent.getHeight(), 0.0f, 1.0f)
            );

            final List<Rect2D> scissors = Collections.singletonList(
                    new Rect2D(0, 0, imageExtent.getWidth(), imageExtent.getHeight())
            );

            final ViewportState viewportState = new ViewportState(viewports, scissors);

            final RasterizationState rasterizationState = new RasterizationState(false, false, PolygonMode.FILL,
                    CullMode.BACK, FrontFace.CLOCKWISE, false, 0.0f, 0.0f, 0.0f, 1.0f);

            final MultisampleState multisampleState = new MultisampleState(SampleCount.ONE, false, 0.0f, false, false);

            final List<ColorBlendAttachmentState> colorBlendAttachmentStates = Collections.singletonList(
                    new ColorBlendAttachmentState(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
                            BlendOperation.ADD, BlendFactor.ONE, BlendFactor.ZERO, BlendOperation.ADD,
                            Stream.of(ColorComponent.R, ColorComponent.G, ColorComponent.B, ColorComponent.A).collect(Collectors.toSet()))
            );

            final ColorBlendState colorBlendState = new ColorBlendState(false, null, colorBlendAttachmentStates, null);

            graphicsPipeline = new GraphicsPipeline(device, null, stages, vertexInputState, inputAssemblyState,
                    viewportState, rasterizationState, multisampleState, null, colorBlendState, renderPass,
                    pipelineLayout, 0);

            framebuffers = swapchainImageViews.stream()
                    .map(Collections::singletonList)
                    .map(attachments -> new Framebuffer(device, renderPass, attachments, imageExtent))
                    .collect(Collectors.toList());

            commandBuffers = commandPool.createCommandBuffers(CommandBufferLevel.PRIMARY, framebuffers.size());

            final Rect2D renderArea = new Rect2D(new Offset2D(0, 0), imageExtent);

            final List<ClearValue> clearValues = Collections.singletonList(
                    new ClearColorFloat(0.0f, 0.0f, 0.0f, 1.0f)
            );

            IntStream.range(0, commandBuffers.size())
                    .forEach(i -> {
                        final CommandBuffer commandBuffer = commandBuffers.get(i);
                        final Framebuffer framebuffer = framebuffers.get(i);

                        commandBuffer.begin();

                        try {
                            commandBuffer.submit(new BeginRenderPassCommand(SubpassContents.INLINE, renderPass,
                                    framebuffer, renderArea, clearValues));
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
                    .filter(format -> format.getFormat() == Format.UNDEFINED).isPresent()) {
                result = new SurfaceFormat(Format.B8G8R8A8_UNORM, ColorSpace.SRGB_NONLINEAR);
            } else {
                result = surfaceFormats.stream()
                        .filter(surfaceFormat -> Format.B8G8R8A8_UNORM.equals(surfaceFormat.getFormat())
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
