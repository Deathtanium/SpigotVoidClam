package com.serbanstein.voidclam;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/** Parse {@code /voidclam} target strings: UUID (with dashes or 32 hex) or three integers {@code x y z}. */
public final class VoidClamCommandArgs {
    private static final SimpleCommandExceptionType BAD_TARGET = new SimpleCommandExceptionType(
        Text.literal("Expected UUID or three integers x y z"));
    private static final SimpleCommandExceptionType UNKNOWN = new SimpleCommandExceptionType(
        Text.literal("No voidclam at that UUID or block position"));

    private VoidClamCommandArgs() {
    }

    public static Module parseTarget(String raw, ServerCommandSource source) throws CommandSyntaxException {
        String s = raw.trim();
        if (s.isEmpty()) throw BAD_TARGET.create();
        Module m = tryUuid(s);
        if (m != null) return m;
        m = tryBlockPos(s);
        if (m != null) return m;
        if (looksLikeUuid(s) || looksLikeBlockPos(s)) {
            throw UNKNOWN.create();
        }
        throw BAD_TARGET.create();
    }

    private static boolean looksLikeUuid(String s) {
        return s.chars().filter(ch -> ch == '-').count() >= 4 || (s.length() == 32 && s.matches("[0-9a-fA-F]+"));
    }

    private static boolean looksLikeBlockPos(String s) {
        return s.matches("-?\\d+\\s+-?\\d+\\s+-?\\d+");
    }

    private static @org.jetbrains.annotations.Nullable Module tryUuid(String s) {
        try {
            UUID id;
            if (s.length() == 32 && !s.contains("-")) {
                id = UUID.fromString(insertDashes(s));
            } else {
                id = UUID.fromString(s);
            }
            return VoidClamMod.getModuleById(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String insertDashes(String hex32) {
        return hex32.substring(0, 8) + "-" + hex32.substring(8, 12) + "-" + hex32.substring(12, 16)
            + "-" + hex32.substring(16, 20) + "-" + hex32.substring(20, 32);
    }

    private static @org.jetbrains.annotations.Nullable Module tryBlockPos(String s) {
        try {
            StringReader reader = new StringReader(s);
            int x = reader.readInt();
            reader.skipWhitespace();
            int y = reader.readInt();
            reader.skipWhitespace();
            int z = reader.readInt();
            reader.skipWhitespace();
            if (reader.canRead()) return null;
            return VoidClamMod.findModuleAt(new BlockPos(x, y, z));
        } catch (CommandSyntaxException e) {
            return null;
        }
    }
}
