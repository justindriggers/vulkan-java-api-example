package com.justindriggers.example;

import com.justindriggers.example.window.GLFWWindow;
import com.justindriggers.example.window.Window;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Application {

    private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

    @SuppressWarnings("squid:S1181")
    public static void main(final String[] args) {
        try (final Window window = new GLFWWindow()) {
            LOGGER.log(Level.INFO, () -> String.format("Window %d created", window.getHandle()));
        } catch (final Throwable t) {
            LOGGER.log(Level.SEVERE, "An fatal error occurred", t);
        }
    }
}
