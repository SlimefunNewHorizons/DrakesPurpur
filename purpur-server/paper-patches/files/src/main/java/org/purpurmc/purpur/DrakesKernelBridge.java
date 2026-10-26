package org.purpurmc.purpur;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DrakesPurpur Kernel Bridge & Crash Guard
 * Architecture: Jack (JackStar6677-1) - DrakesCraft Labs
 */
public final class DrakesKernelBridge {
    private static final Logger LOGGER = Logger.getLogger("DrakesKernel");
    private static volatile boolean rustLoaded = false;

    private DrakesKernelBridge() {}

    public static void initialize() {
        LOGGER.info("[DrakesPurpur] Inicializando Kernel Soberano 26.2...");
        loadRustNativeBridge();
    }

    private static void loadRustNativeBridge() {
        try {
            File nativeLib = new File("natives/libslimefun_ffi.so");
            if (nativeLib.exists()) {
                System.load(nativeLib.getAbsolutePath());
                rustLoaded = true;
                LOGGER.info("[DrakesPurpur] Motor Rust FFM cargado exitosamente off-heap: " + nativeLib.getAbsolutePath());
            } else {
                LOGGER.info("[DrakesPurpur] libslimefun_ffi.so no detectado en natives/. Modo simulado activo.");
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[DrakesPurpur] Advertencia cargando puente nativo Rust: " + t.getMessage());
        }
    }

    public static boolean isRustLoaded() {
        return rustLoaded;
    }

    public static void handleUncaughtFault(String context, Object source, Throwable fault) {
        LOGGER.log(Level.SEVERE, "[CrashGuard] Excepcion no capturada en " + context + " (" + source + "): " + fault.getMessage());
    }
}
