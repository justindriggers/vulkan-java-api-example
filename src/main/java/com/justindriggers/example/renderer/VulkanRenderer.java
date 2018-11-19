package com.justindriggers.example.renderer;

import com.justindriggers.example.renderer.device.PhysicalDeviceMetadata;
import com.justindriggers.example.renderer.swapchain.SwapchainManager;
import com.justindriggers.example.renderer.swapchain.SwapchainManagerImpl;
import com.justindriggers.vulkan.command.CommandBuffer;
import com.justindriggers.vulkan.command.CommandPool;
import com.justindriggers.vulkan.command.models.CommandPoolCreateFlag;
import com.justindriggers.vulkan.devices.logical.LogicalDevice;
import com.justindriggers.vulkan.devices.physical.PhysicalDevice;
import com.justindriggers.vulkan.instance.VulkanInstance;
import com.justindriggers.vulkan.instance.models.VulkanException;
import com.justindriggers.vulkan.models.pointers.Disposable;
import com.justindriggers.vulkan.pipeline.models.PipelineStage;
import com.justindriggers.vulkan.pipeline.shader.ShaderModule;
import com.justindriggers.vulkan.pipeline.shader.ShaderModuleLoader;
import com.justindriggers.vulkan.queue.Queue;
import com.justindriggers.vulkan.queue.QueueFamily;
import com.justindriggers.vulkan.surface.Surface;
import com.justindriggers.vulkan.swapchain.Swapchain;
import com.justindriggers.vulkan.synchronize.Fence;
import com.justindriggers.vulkan.synchronize.Semaphore;
import com.justindriggers.vulkan.synchronize.models.FenceCreationFlag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;

public class VulkanRenderer implements Renderer {

    private static final Logger LOGGER = Logger.getLogger(VulkanRenderer.class.getName());

    private static final Set<String> DEVICE_EXTENSIONS = Stream.of(
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
    ).collect(Collectors.toSet());

    private static final int MAX_IN_FLIGHT_FRAMES = 2;

    private AtomicInteger currentFrameCounter = new AtomicInteger(0);
    private AtomicBoolean isDirty = new AtomicBoolean(false);

    private final Surface surface;

    private final LogicalDevice device;

    private final PhysicalDeviceMetadata chosenPhysicalDeviceMetadata;

    private final Queue graphicsQueue;
    private final Queue presentationQueue;

    private final ShaderModule vertexShader;
    private final ShaderModule fragmentShader;

    private final CommandPool commandPool;

    private final SwapchainManager swapchainManager;

    private final List<Semaphore> imageAcquiredSemaphores;
    private final List<Semaphore> renderCompleteSemaphores;
    private final List<Fence> inFlightFences;

    public VulkanRenderer(final VulkanInstance instance, final Surface surface) {
        this.surface = surface;

        final List<PhysicalDevice> physicalDevices = Optional.ofNullable(instance.getPhysicalDevices())
                .orElseGet(Collections::emptyList);

        chosenPhysicalDeviceMetadata = getMostSuitablePhysicalDeviceMetadata(physicalDevices, surface);

        final PhysicalDevice chosenPhysicalDevice = chosenPhysicalDeviceMetadata.getPhysicalDevice();
        final QueueFamily graphicsQueueFamily = chosenPhysicalDeviceMetadata.getGraphicsQueueFamily();
        final QueueFamily presentationQueueFamily = chosenPhysicalDeviceMetadata.getPresentationQueueFamily();

        device = createLogicalDevice(chosenPhysicalDevice, graphicsQueueFamily, presentationQueueFamily);

        graphicsQueue = device.getQueue(graphicsQueueFamily, 0);
        presentationQueue = device.getQueue(presentationQueueFamily, 0);

        ShaderModuleLoader shaderModuleLoader = new ShaderModuleLoader();
        vertexShader = shaderModuleLoader.loadFromFile(device, "triangle.vert.spv");
        fragmentShader = shaderModuleLoader.loadFromFile(device, "triangle.frag.spv");

        commandPool = new CommandPool(device, graphicsQueueFamily, CommandPoolCreateFlag.RESET_COMMAND_BUFFER);

        swapchainManager = new SwapchainManagerImpl(commandPool);
        recreateSwapchain();

        imageAcquiredSemaphores = new ArrayList<>(MAX_IN_FLIGHT_FRAMES);
        renderCompleteSemaphores = new ArrayList<>(MAX_IN_FLIGHT_FRAMES);
        inFlightFences = new ArrayList<>(MAX_IN_FLIGHT_FRAMES);

        IntStream.of(0, MAX_IN_FLIGHT_FRAMES)
                .forEach(i -> {
                    imageAcquiredSemaphores.add(new Semaphore(device));
                    renderCompleteSemaphores.add(new Semaphore(device));
                    inFlightFences.add(new Fence(device, FenceCreationFlag.SIGNALED));
                });
    }

    @Override
    public void renderFrame() {
        if (isDirty.getAndSet(false)) {
            recreateSwapchain();
        }

        final int currentFrame = currentFrameCounter.getAndUpdate(i -> (i + 1) % MAX_IN_FLIGHT_FRAMES);

        final Semaphore imageAcquiredSemaphore = imageAcquiredSemaphores.get(currentFrame);
        final Semaphore renderCompleteSemaphore = renderCompleteSemaphores.get(currentFrame);
        final Fence inFlightFence = inFlightFences.get(currentFrame);

        try {
            // Wait until the last graphics queue submission for this fence has completed
            inFlightFence.waitForSignal();

            final Swapchain currentSwapchain = swapchainManager.getCurrentSwapchain();
            final List<CommandBuffer> currentCommandBuffers = swapchainManager.getCurrentCommandBuffers();

            final int nextImageIndex = currentSwapchain.acquireNextImageIndex(imageAcquiredSemaphore, null);

            final CommandBuffer commandBuffer = currentCommandBuffers.get(nextImageIndex);

            // Don't reset the fence until we have successfully acquired the next image index.
            // If we were to reset the fence first and the next image acquisition failed, then we would have to
            // construct a new fence in order to continue, since the current fence would never enter the signaled state.
            inFlightFence.reset();

            graphicsQueue.submit(
                    Collections.singletonList(imageAcquiredSemaphore),
                    Collections.singletonList(PipelineStage.COLOR_ATTACHMENT_OUTPUT),
                    Collections.singleton(commandBuffer),
                    Collections.singleton(renderCompleteSemaphore),
                    inFlightFence
            );

            presentationQueue.present(
                    Collections.singletonList(currentSwapchain),
                    Collections.singletonList(nextImageIndex),
                    Collections.singleton(renderCompleteSemaphore)
            );
        } catch (final VulkanException e) {
            switch (e.getResult()) {
                case ERROR_OUT_OF_DATE:
                case SUBOPTIMAL:
                    refresh();
                    break;
                default:
                    throw e;
            }
        }
    }

    @Override
    public void refresh() {
        isDirty.set(true);
    }

    @Override
    public void close() throws IOException {
        Optional.ofNullable(device).ifPresent(LogicalDevice::waitIdle);

        swapchainManager.close();

        imageAcquiredSemaphores.forEach(Disposable::close);
        renderCompleteSemaphores.forEach(Disposable::close);
        inFlightFences.forEach(Disposable::close);

        Stream.of(commandPool, fragmentShader, vertexShader, device)
                .filter(Objects::nonNull)
                .map(Disposable.class::cast)
                .forEachOrdered(Disposable::close);
    }

    private void recreateSwapchain() {
        device.waitIdle();

        swapchainManager.refresh(surface, chosenPhysicalDeviceMetadata, device, vertexShader, fragmentShader);
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
}
