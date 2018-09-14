package com.justindriggers.example.renderer;

import com.justindriggers.vulkan.devices.logical.LogicalDevice;
import com.justindriggers.vulkan.devices.physical.PhysicalDevice;
import com.justindriggers.vulkan.instance.DebugLogger;
import com.justindriggers.vulkan.instance.VulkanInstance;
import com.justindriggers.vulkan.instance.models.MessageSeverity;
import com.justindriggers.vulkan.instance.models.MessageType;
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
import com.justindriggers.vulkan.pipeline.models.PipelineStage;
import com.justindriggers.vulkan.pipeline.shader.ShaderModule;
import com.justindriggers.vulkan.pipeline.shader.ShaderModuleLoader;
import com.justindriggers.vulkan.queue.Queue;
import com.justindriggers.vulkan.queue.QueueCapability;
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
import com.justindriggers.vulkan.synchronize.Fence;
import com.justindriggers.vulkan.synchronize.Semaphore;
import com.justindriggers.vulkan.synchronize.models.FenceCreationFlag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;

public class VulkanRenderer implements Renderer {

    private static final Logger LOGGER = Logger.getLogger(VulkanRenderer.class.getName());

    private static final Set<MessageSeverity> MESSAGE_SEVERITIES = Stream.of(
            MessageSeverity.VERBOSE,
            MessageSeverity.INFO,
            MessageSeverity.WARNING,
            MessageSeverity.ERROR
    ).collect(Collectors.toCollection(() -> EnumSet.noneOf(MessageSeverity.class)));

    private static final Set<MessageType> MESSAGE_TYPES = Stream.of(
            MessageType.GENERAL,
            MessageType.PERFORMANCE,
            MessageType.VALIDATION
    ).collect(Collectors.toCollection(() -> EnumSet.noneOf(MessageType.class)));

    private static final Set<String> VALIDATION_LAYERS = Collections.singleton("VK_LAYER_LUNARG_standard_validation");
    private static final Set<String> INSTANCE_EXTENSIONS = Collections.singleton(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);

    private static final int MAX_IN_FLIGHT_FRAMES = 2;

    private final VulkanInstance instance;
    private final Surface surface;
    private final LogicalDevice device;

    private final Queue graphicsQueue;
    private final Queue presentationQueue;

    private final ShaderModule vertexShader;
    private final ShaderModule fragmentShader;

    private final CommandPool commandPool;

    private final Swapchain swapchain;
    private final List<ImageView> swapchainImageViews;

    private final RenderPass renderPass;
    private final PipelineLayout pipelineLayout;
    private final GraphicsPipeline graphicsPipeline;

    private final List<Framebuffer> framebuffers;
    private final List<CommandBuffer> commandBuffers;

    private final List<Semaphore> imageAcquiredSemaphores;
    private final List<Semaphore> renderCompleteSemaphores;
    private final List<Fence> inFlightFences;

    private AtomicInteger currentFrameCounter = new AtomicInteger(0);

    public VulkanRenderer(final long windowHandle,
                          final int windowWidth,
                          final int windowHeight) {
        instance = new VulkanInstance(INSTANCE_EXTENSIONS, VALIDATION_LAYERS);

        if (!MESSAGE_SEVERITIES.isEmpty() && !MESSAGE_TYPES.isEmpty()) {
            instance.enableDebugging(MESSAGE_SEVERITIES, MESSAGE_TYPES, new DebugLogger());
        }

        surface = new Surface(instance, windowHandle);

        final List<PhysicalDevice> physicalDevices = Optional.ofNullable(instance.getPhysicalDevices())
                .orElseGet(Collections::emptyList);

        final PhysicalDeviceMetadata chosenPhysicalDeviceMetadata = getMostSuitablePhysicalDeviceMetadata(
                physicalDevices, surface);

        final PhysicalDevice chosenPhysicalDevice = chosenPhysicalDeviceMetadata.getPhysicalDevice();
        final QueueFamily graphicsQueueFamily = chosenPhysicalDeviceMetadata.getGraphicsQueueFamily();
        final QueueFamily presentationQueueFamily = chosenPhysicalDeviceMetadata.getPresentationQueueFamily();

        final SurfaceCapabilities surfaceCapabilities = surface.getCapabilities(chosenPhysicalDevice);

        final int imageCount = getImageCount(surfaceCapabilities);
        final Extent2D imageExtent = getImageExtent(surfaceCapabilities, windowWidth, windowHeight);

        final List<SurfaceFormat> surfaceFormats = surface.getFormats(chosenPhysicalDevice);
        final SurfaceFormat chosenSurfaceFormat = getBestSurfaceFormat(surfaceFormats);

        final Set<PresentMode> presentModes = surface.getPresentModes(chosenPhysicalDevice);
        final PresentMode chosenPresentMode = getBestPresentMode(presentModes);

        device = createLogicalDevice(chosenPhysicalDevice, graphicsQueueFamily, presentationQueueFamily);

        graphicsQueue = device.getQueue(graphicsQueueFamily, 0);
        presentationQueue = device.getQueue(presentationQueueFamily, 0);

        ShaderModuleLoader shaderModuleLoader = new ShaderModuleLoader();
        vertexShader = shaderModuleLoader.loadFromFile(device, "triangle.vert.spv");
        fragmentShader = shaderModuleLoader.loadFromFile(device, "triangle.frag.spv");

        commandPool = new CommandPool(device, graphicsQueueFamily);

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

        imageAcquiredSemaphores = new ArrayList<>(MAX_IN_FLIGHT_FRAMES);
        renderCompleteSemaphores = new ArrayList<>(MAX_IN_FLIGHT_FRAMES);
        inFlightFences = new ArrayList<>(MAX_IN_FLIGHT_FRAMES);

        IntStream.of(0, MAX_IN_FLIGHT_FRAMES)
                .forEach(i -> {
                    imageAcquiredSemaphores.add(new Semaphore(device));
                    renderCompleteSemaphores.add(new Semaphore(device));
                    inFlightFences.add(new Fence(device, Collections.singleton(FenceCreationFlag.SIGNALED)));
                });
    }

    @Override
    public void resize(int width, int height) {
        // TODO Recreate Swapchain
    }

    @Override
    public void renderFrame() {
        final int currentFrame = currentFrameCounter.getAndUpdate(i -> (i + 1) % MAX_IN_FLIGHT_FRAMES);

        final Semaphore imageAcquiredSemaphore = imageAcquiredSemaphores.get(currentFrame);
        final Semaphore renderCompleteSemaphore = renderCompleteSemaphores.get(currentFrame);
        final Fence inFlightFence = inFlightFences.get(currentFrame);

        inFlightFence.waitForSignal();
        inFlightFence.reset();

        final int nextImageIndex = swapchain.acquireNextImageIndex(imageAcquiredSemaphore, null);

        final CommandBuffer commandBuffer = commandBuffers.get(nextImageIndex);

        graphicsQueue.submit(
                Collections.singletonList(imageAcquiredSemaphore),
                Collections.singletonList(PipelineStage.COLOR_ATTACHMENT_OUTPUT),
                Collections.singleton(commandBuffer),
                Collections.singleton(renderCompleteSemaphore),
                inFlightFence
        );

        presentationQueue.present(
                Collections.singletonList(swapchain),
                Collections.singletonList(nextImageIndex),
                Collections.singleton(renderCompleteSemaphore)
        );
    }

    @Override
    public void close() {
        Optional.ofNullable(device).ifPresent(LogicalDevice::waitIdle);

        imageAcquiredSemaphores.forEach(semaphore -> {
            try {
                semaphore.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to close semaphore");
            }
        });

        renderCompleteSemaphores.forEach(semaphore -> {
            try {
                semaphore.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to close semaphore");
            }
        });

        inFlightFences.forEach(fence -> {
            try {
                fence.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to close fence");
            }
        });

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

        Stream.of(graphicsPipeline, pipelineLayout, renderPass, swapchain, commandPool, fragmentShader, vertexShader,
                device, surface, instance)
                .filter(Objects::nonNull)
                .forEachOrdered(closeable -> {
                    try {
                        closeable.close();
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to close " + closeable.getClass().getName());
                    }
                });
    }

    private static PhysicalDeviceMetadata getMostSuitablePhysicalDeviceMetadata(final List<PhysicalDevice> physicalDevices,
                                                                                final Surface surface) {

        final PhysicalDeviceMetadata mostSuitablePhysicalDevice = physicalDevices.stream()
                .map(physicalDevice -> new PhysicalDeviceMetadata(physicalDevice, surface))
                .filter(metadata -> metadata.calculateScore() > 0) // Filter out unsuitable devices
                .max(Comparator.comparingInt(PhysicalDeviceMetadata::calculateScore))
                .orElseThrow(() -> new IllegalStateException("Unable to find suitable physical device"));

        LOGGER.log(Level.INFO, () -> String.format("Using [%s] with device score of %d",
                mostSuitablePhysicalDevice.getPhysicalDevice().getProperties().getDeviceName(),
                mostSuitablePhysicalDevice.calculateScore()));

        return mostSuitablePhysicalDevice;
    }

    private static LogicalDevice createLogicalDevice(final PhysicalDevice physicalDevice,
                                                     final QueueFamily graphicsQueueFamily,
                                                     final QueueFamily presentationQueueFamily) {
        final Map<QueueFamily, List<Float>> queueFamilyQueuePriorities = new HashMap<>();

        final List<Float> graphicsQueueFamilyPriorities = IntStream.range(0, graphicsQueueFamily.getQueueCount())
                .mapToObj(i -> 1.0f)
                .collect(Collectors.toList());

        queueFamilyQueuePriorities.put(graphicsQueueFamily, graphicsQueueFamilyPriorities);

        if (!graphicsQueueFamily.equals(presentationQueueFamily)) {
            final List<Float> presentationQueueFamilyPriorities = IntStream.range(0, presentationQueueFamily.getQueueCount())
                    .mapToObj(i -> 1.0f)
                    .collect(Collectors.toList());

            queueFamilyQueuePriorities.put(presentationQueueFamily, presentationQueueFamilyPriorities);
        }

        return new LogicalDevice(physicalDevice, queueFamilyQueuePriorities, DEVICE_EXTENSIONS);
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
                                    final int windowWidth,
                                    final int windowHeight) {
        final Extent2D result;

        if (surfaceCapabilities.getCurrentExtent().getWidth() != Integer.MAX_VALUE) {
            result = surfaceCapabilities.getCurrentExtent();
        } else {
            final int width = Math.max(
                    surfaceCapabilities.getMinImageExtent().getWidth(),
                    Math.min(
                            surfaceCapabilities.getMaxImageExtent().getWidth(),
                            windowWidth
                    )
            );

            final int height = Math.max(
                    surfaceCapabilities.getMinImageExtent().getHeight(),
                    Math.min(
                            surfaceCapabilities.getMaxImageExtent().getHeight(),
                            windowHeight
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

    private static class PhysicalDeviceMetadata {

        private final PhysicalDevice physicalDevice;
        private final QueueFamily graphicsQueueFamily;
        private final QueueFamily presentationQueueFamily;

        PhysicalDeviceMetadata(final PhysicalDevice physicalDevice, final Surface surface) {
            this.physicalDevice = physicalDevice;

            final Set<QueueFamily> queueFamilies = Optional.ofNullable(physicalDevice.getQueueFamilies())
                    .orElseGet(Collections::emptySet);

            graphicsQueueFamily = queueFamilies.stream()
                    .filter(queueFamily -> queueFamily.getQueueCount() > 0)
                    .filter(queueFamily -> queueFamily.getCapabilities().contains(QueueCapability.GRAPHICS))
                    .findFirst()
                    .orElse(null);

            presentationQueueFamily = queueFamilies.stream()
                    .filter(queueFamily -> queueFamily.getQueueCount() > 0)
                    .filter(queueFamily -> queueFamily.supportsSurfacePresentation(surface))
                    .findFirst()
                    .orElse(null);
        }

        PhysicalDevice getPhysicalDevice() {
            return physicalDevice;
        }

        QueueFamily getGraphicsQueueFamily() {
            return graphicsQueueFamily;
        }

        QueueFamily getPresentationQueueFamily() {
            return presentationQueueFamily;
        }

        int calculateScore() {
            int result = 1;

            if (graphicsQueueFamily == null || presentationQueueFamily == null) {
                result = 0; // Incompatible for this demo
            } else if (graphicsQueueFamily.getIndex() == presentationQueueFamily.getIndex()) {
                result += 1; // Prefer when the graphics queue family supports presentation
            }

            return result;
        }
    }
}
