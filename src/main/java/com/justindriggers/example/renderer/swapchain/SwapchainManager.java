package com.justindriggers.example.renderer.swapchain;

import com.justindriggers.example.renderer.device.PhysicalDeviceMetadata;
import com.justindriggers.vulkan.command.CommandBuffer;
import com.justindriggers.vulkan.devices.logical.LogicalDevice;
import com.justindriggers.vulkan.pipeline.shader.ShaderModule;
import com.justindriggers.vulkan.surface.Surface;
import com.justindriggers.vulkan.swapchain.Swapchain;

import java.io.Closeable;
import java.util.List;

public interface SwapchainManager extends Closeable {

    void refresh(final Surface surface,
                 final PhysicalDeviceMetadata physicalDeviceMetadata,
                 final LogicalDevice device,
                 final ShaderModule vertexShader,
                 final ShaderModule fragmentShader);

    Swapchain getCurrentSwapchain();

    List<CommandBuffer> getCurrentCommandBuffers();
}
