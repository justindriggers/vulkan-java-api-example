package com.justindriggers.example.window;

import com.justindriggers.example.renderer.Renderer;
import com.justindriggers.example.renderer.VulkanRenderer;
import com.justindriggers.glfw.GLFWInstance;
import com.justindriggers.vulkan.instance.DebugLogger;
import com.justindriggers.vulkan.instance.VulkanInstance;
import com.justindriggers.vulkan.instance.models.ApplicationInfo;
import com.justindriggers.vulkan.instance.models.MessageSeverity;
import com.justindriggers.vulkan.instance.models.MessageType;
import com.justindriggers.vulkan.instance.models.VulkanVersion;
import com.justindriggers.vulkan.surface.Surface;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;

public class GLFWWindow implements Window {

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

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;

    private final long windowHandle;
    private final VulkanInstance vulkanInstance;
    private final Surface surface;
    private final Renderer renderer;
    private final GLFWKeyCallback keyCallback;
    private final GLFWFramebufferSizeCallback framebufferSizeCallback;

    private int currentWidth;
    private int currentHeight;

    public GLFWWindow() {
        final GLFWInstance glfwInstance = new GLFWInstance();

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW.GLFW_NO_API);

        final ApplicationInfo applicationInfo = new ApplicationInfo(
                "vulkan-java-api-example", 1,
                null, 0,
                new VulkanVersion(1, 1, 0)
        );

        windowHandle = glfwCreateWindow(WIDTH, HEIGHT, applicationInfo.getApplicationName(), NULL, NULL);

        currentWidth = WIDTH;
        currentHeight = HEIGHT;

        final Set<String> instanceExtensions = Stream.of(
                glfwInstance.getRequiredVulkanInstanceExtensions(),
                INSTANCE_EXTENSIONS
        ).flatMap(Collection::stream).collect(Collectors.toSet());

        vulkanInstance = new VulkanInstance(applicationInfo, instanceExtensions, VALIDATION_LAYERS);

        if (!MESSAGE_SEVERITIES.isEmpty() && !MESSAGE_TYPES.isEmpty()) {
            vulkanInstance.enableDebugging(MESSAGE_SEVERITIES, MESSAGE_TYPES, new DebugLogger());
        }

        surface = glfwInstance.createWindowSurface(vulkanInstance, windowHandle);

        renderer = new VulkanRenderer(vulkanInstance, surface);

        keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(final long window, final int key, final int scancode, final int action, final int mods) {
                if (action != GLFW_RELEASE && key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                }
            }
        };

        glfwSetKeyCallback(windowHandle, keyCallback);

        framebufferSizeCallback = new GLFWFramebufferSizeCallback() {
            @Override
            public void invoke(final long window, final int width, final int height) {
                if (windowHandle == window) {
                    currentWidth = width;
                    currentHeight = height;

                    renderer.refresh();
                }
            }
        };

        glfwSetFramebufferSizeCallback(windowHandle, framebufferSizeCallback);

        glfwShowWindow(windowHandle);

        while (!glfwWindowShouldClose(windowHandle)) {
            glfwPollEvents();

            renderer.renderFrame();
        }
    }

    @Override
    public int getWidth() {
        return currentWidth;
    }

    @Override
    public int getHeight() {
        return currentHeight;
    }

    @Override
    public long getHandle() {
        return windowHandle;
    }

    @Override
    public void close() throws IOException {
        renderer.close();

        framebufferSizeCallback.free();
        keyCallback.free();

        surface.close();
        vulkanInstance.close();

        glfwDestroyWindow(windowHandle);
        glfwTerminate();
    }
}
