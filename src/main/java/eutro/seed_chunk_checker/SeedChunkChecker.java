package eutro.seed_chunk_checker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.server.Main;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.AbstractPropertiesHandler;
import net.minecraft.server.dedicated.ServerPropertiesLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;

public class SeedChunkChecker {

    private static final MethodHandle THREAD_TARGET;
    private static final MethodHandle APH_PROPERTIES;
    private static MethodHandle CLOSURE_FIELD;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            Field target = Thread.class.getDeclaredField("target");
            target.setAccessible(true);
            THREAD_TARGET = lookup.unreflectGetter(target);
            Field properties;
            try {
                properties = AbstractPropertiesHandler.class.getDeclaredField("properties");
            } catch (NoSuchFieldException ignored) {
                //noinspection JavaReflectionMemberAccess
                properties = AbstractPropertiesHandler.class.getDeclaredField("field_16848");
            }
            properties.setAccessible(true);
            APH_PROPERTIES = lookup.unreflectGetter(properties);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        String seed = args[0];
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        File file = new File(seed);
        PrintStream err = System.err;
        if (!file.mkdir()) {
            err.printf("Directory %s already exists (seed may have already been checked)%n", seed);
        }
        setSeed(seed);
        MinecraftServer server = startDedicatedServer("--nogui", "--world", seed);
        ServerWorld world;
        while ((world = server.getWorld(World.OVERWORLD)) == null) {
            try {
                //noinspection BusyWait - cry about it
                Thread.sleep(0);
            } catch (InterruptedException ignored) {
            }
        }
        File outFile = new File(file, "frequency.json");
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject obj = new JsonObject();
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            obj.add("frequencies", checkChunk(world, x, y));
            try (JsonWriter writer = gson.newJsonWriter(new FileWriter(outFile))) {
                gson.toJson(obj, writer);
            }
            err.println("Saved to " + outFile);
        } catch (IOException e) {
            err.println("Failed to save " + outFile);
            e.printStackTrace(err);
        }
        server.stop(true);
    }

    private static JsonObject checkChunk(ServerWorld world, int x, int y) {
        Object2IntMap<Block> counts = new Object2IntOpenHashMap<>();
        ChunkPos chunkPos = new ChunkPos(x, y);
        WorldChunk chunk = world.getChunk(x, y);
        for (BlockPos pos : BlockPos.iterate(
                chunkPos.toBlockPos(0, 0, 0),
                chunkPos.toBlockPos(15, 255, 15)
        )) {
            Block state = chunk.getBlockState(pos).getBlock();
            counts.put(state, counts.getInt(state) + 1);
        }
        JsonObject obj = new JsonObject();
        counts.object2IntEntrySet()
                .stream()
                .sorted(Comparator.comparingInt((ToIntFunction<? super Object2IntMap.Entry<?>>) Object2IntMap.Entry::getIntValue).reversed())
                .forEachOrdered(entry -> obj.addProperty(Registry.BLOCK.getId(entry.getKey()).toString(), entry.getIntValue()));
        return obj;
    }

    private static void setSeed(String seed) {
        ServerPropertiesLoader properties = new ServerPropertiesLoader(new File("server.properties").toPath());
        Properties props;
        try {
            props = (Properties) APH_PROPERTIES.invokeExact((AbstractPropertiesHandler<?>) properties.getPropertiesHandler());
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        props.setProperty("level-seed", seed);
        properties.store();
    }

    private static MinecraftServer startDedicatedServer(String... args) {
        Main.main(args);
        return getServer();
    }

    private static MinecraftServer getServer() {
        // I have the best hacks.
        Thread serverThread = Thread.getAllStackTraces()
                .keySet()
                .stream()
                .filter(thread -> "Server thread".equals(thread.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Couldn't find server thread"));
        try {
            Runnable runnable = (Runnable) THREAD_TARGET.invokeExact(serverThread);
            if (CLOSURE_FIELD == null) {
                Field closureField = Arrays.stream(runnable.getClass().getDeclaredFields())
                        .filter(field -> AtomicReference.class.isAssignableFrom(field.getType()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Couldn't find server field"));
                closureField.setAccessible(true);
                CLOSURE_FIELD = MethodHandles.lookup().unreflectGetter(closureField);
            }
            return (MinecraftServer) ((AtomicReference<?>) CLOSURE_FIELD.invoke(runnable)).get();
        } catch (Throwable e) {
            throw new IllegalStateException("Couldn't get running server", e);
        }
    }
}
