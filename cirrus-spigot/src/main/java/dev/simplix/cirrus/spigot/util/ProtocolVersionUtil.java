package dev.simplix.cirrus.spigot.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProtocolVersionUtil {

    private static int protocolVersion;

    public static int serverProtocolVersion() {
        if (protocolVersion == 0) {
            protocolVersion = detectVersion();
        }
        return protocolVersion;

    }

    private static int detectVersion() {
        String majorVersion = ReflectionUtil
                .serverVersion()
                .substring(1, ReflectionUtil.serverVersion().indexOf('_', 3));
        try {
            Field field = dev.simplix.cirrus.spigot.util.ProtocolVersions.class.getField("MINECRAFT_" + majorVersion);
            return field.getInt(null);
        } catch (IllegalAccessException exception) {
            log.error("Could not access field MINECRAFT_" + majorVersion, exception);
        } catch (NoSuchFieldException exception) {
            log.warn("[Cirrus] Cirrus is not compatible with this version ", exception);
            log.warn("[Cirrus] [Compatibility Mode] Proceeding as if in Minecraft 1.18");
        }
        return ProtocolVersions.MINECRAFT_1_18_1;
    }

}
