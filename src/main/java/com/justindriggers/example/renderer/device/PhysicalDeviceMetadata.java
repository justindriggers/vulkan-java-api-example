package com.justindriggers.example.renderer.device;

import com.justindriggers.vulkan.devices.physical.PhysicalDevice;
import com.justindriggers.vulkan.queue.QueueCapability;
import com.justindriggers.vulkan.queue.QueueFamily;
import com.justindriggers.vulkan.surface.Surface;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class PhysicalDeviceMetadata {

    private final PhysicalDevice physicalDevice;
    private final QueueFamily graphicsQueueFamily;
    private final QueueFamily presentationQueueFamily;

    public PhysicalDeviceMetadata(final PhysicalDevice physicalDevice, final Surface surface) {
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

    public PhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    public QueueFamily getGraphicsQueueFamily() {
        return graphicsQueueFamily;
    }

    public QueueFamily getPresentationQueueFamily() {
        return presentationQueueFamily;
    }

    public int calculateScore() {
        int result = 1;

        if (graphicsQueueFamily == null || presentationQueueFamily == null) {
            result = 0; // Incompatible for this demo
        } else if (graphicsQueueFamily.getIndex() == presentationQueueFamily.getIndex()) {
            result += 1; // Prefer when the graphics queue family supports presentation
        }

        return result;
    }
}
