package dev.lodgen.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.concurrent.Executor;

@Mixin(MinecraftServer.class)
public interface ServerExecutorAccess {
    @Accessor("executor") Executor lodgen$executor();
}
