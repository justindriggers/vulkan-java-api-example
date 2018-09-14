package com.justindriggers.example.renderer.swapchain;

import com.justindriggers.example.renderer.device.PhysicalDeviceMetadata;
import com.justindriggers.example.window.Window;
import com.justindriggers.vulkan.devices.logical.LogicalDevice;
import com.justindriggers.vulkan.pipeline.CommandBuffer;
import com.justindriggers.vulkan.pipeline.shader.ShaderModule;
import com.justindriggers.vulkan.surface.Surface;
import com.justindriggers.vulkan.swapchain.Swapchain;

import java.util.List;

public interface SwapchainManager extends AutoCloseable {

    void refresh(final Window window,
                 final Surface surface,
                 final PhysicalDeviceMetadata physicalDeviceMetadata,
                 final LogicalDevice device,
                 final ShaderModule vertexShader,
                 final ShaderModule fragmentShader);

    Swapchain getCurrentSwapchain();

    List<CommandBuffer> getCurrentCommandBuffers();
}
