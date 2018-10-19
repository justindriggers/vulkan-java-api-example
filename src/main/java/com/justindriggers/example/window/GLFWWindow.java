package com.justindriggers.example.window;

import com.justindriggers.example.renderer.Renderer;
import com.justindriggers.example.renderer.VulkanRenderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;

import java.io.IOException;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.system.MemoryUtil.NULL;

public class GLFWWindow implements Window {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;

    private final long windowHandle;
    private final Renderer renderer;
    private final GLFWKeyCallback keyCallback;
    private final GLFWFramebufferSizeCallback framebufferSizeCallback;

    private int currentWidth;
    private int currentHeight;

    public GLFWWindow() {
        if (!glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW.GLFW_NO_API);

        windowHandle = glfwCreateWindow(WIDTH, HEIGHT, "GLFW Vulkan Demo", NULL, NULL);

        currentWidth = WIDTH;
        currentHeight = HEIGHT;

        renderer = new VulkanRenderer(this);

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

        glfwDestroyWindow(windowHandle);
        glfwTerminate();
    }
}
