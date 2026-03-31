package com.serbanstein.voidclam;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/** Parse {@code /voidclam} target strings: UUID (with dashes or 32 hex) or three integers {@code x y z}. */
public final class VoidClamCommandArgs {
    private static final SimpleCommandExceptionType BAD_TARGET = new SimpleCommandExceptionType(
        new LiteralText("Expected UUID or three integers x y z"));
    private static final SimpleCommandExceptionType UNKNOWN = new SimpleCommandExceptionType(
        new LiteralText("No voidclam at that UUID or block position"));

    private VoidClamCommandArgs() {
    }

    public static Clam parseTarget(String raw, ServerCommandSource source) throws CommandSyntaxException {
        String s = raw.trim();
        if (s.isEmpty()) throw BAD_TARGET.create();
        Clam m = tryUuid(s);
        if (m != null) return m;
        m = tryBlockPos(s, source);
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

    private static @org.jetbrains.annotations.Nullable Clam tryUuid(String s) {
        try {
            UUID id;
            if (s.length() == 32 && !s.contains("-")) {
                id = UUID.fromString(insertDashes(s));
            } else {
                id = UUID.fromString(s);
            }
            return VoidClamMod.getClamById(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String insertDashes(String hex32) {
        return hex32.substring(0, 8) + "-" + hex32.substring(8, 12) + "-" + hex32.substring(12, 16)
            + "-" + hex32.substring(16, 20) + "-" + hex32.substring(20, 32);
    }

    private static @org.jetbrains.annotations.Nullable Clam tryBlockPos(String s, ServerCommandSource source) {
        try {
            StringReader reader = new StringReader(s);
            int x = reader.readInt();
            reader.skipWhitespace();
            int y = reader.readInt();
            reader.skipWhitespace();
            int z = reader.readInt();
            reader.skipWhitespace();
            if (reader.canRead()) return null;
            ServerWorld world = source.getWorld();
            return VoidClamMod.findClamAt(world, new BlockPos(x, y, z));
        } catch (CommandSyntaxException e) {
            return null;
        }
    }
}
