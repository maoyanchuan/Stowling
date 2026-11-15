package dev.stowling.client.automation;

import dev.stowling.automation.ContainerCategory;
import dev.stowling.automation.ContainerCategoryPort;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MinecraftContainerCategoryResolver
        implements ContainerCategoryPort {
    private static final double SEARCH_RADIUS = 2.0;
    private static final double MAX_DISTANCE_SQUARED = 4.0;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;

    public MinecraftContainerCategoryResolver(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public ContainerCategoryResult match(List<ContainerTarget> containers) {
        Objects.requireNonNull(containers, "containers");
        if (!scheduler.isClientThread()) {
            return ContainerCategoryResult.failure("容器分类必须在客户端线程执行");
        }
        ClientLevel level = minecraft.level;
        if (level == null) {
            return ContainerCategoryResult.failure("当前没有可识别的游戏世界");
        }
        try {
            List<Candidate> candidates = candidates(level, containers);
            candidates.sort(Candidate.ORDER);
            Set<Integer> usedContainers = new HashSet<>();
            Set<UUID> usedFrames = new HashSet<>();
            List<ContainerCategory> categories = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (!usedContainers.add(candidate.containerIndex())
                        || !usedFrames.add(candidate.frameId())) {
                    continue;
                }
                categories.add(new ContainerCategory(
                        containers.get(candidate.containerIndex()),
                        candidate.itemId()
                ));
            }
            return ContainerCategoryResult.success(List.copyOf(categories));
        } catch (RuntimeException exception) {
            return ContainerCategoryResult.failure(
                    "识别容器分类时发生错误: " + exception.getMessage()
            );
        }
    }

    private static List<Candidate> candidates(
            ClientLevel level,
            List<ContainerTarget> containers
    ) {
        List<Candidate> result = new ArrayList<>();
        for (int index = 0; index < containers.size(); index++) {
            ContainerTarget target = containers.get(index);
            Vec3 center = center(target);
            AABB searchArea = new AABB(
                    center.x - SEARCH_RADIUS,
                    center.y - SEARCH_RADIUS,
                    center.z - SEARCH_RADIUS,
                    center.x + SEARCH_RADIUS,
                    center.y + SEARCH_RADIUS,
                    center.z + SEARCH_RADIUS
            );
            List<ItemFrame> frames = level.getEntities(
                    EntityTypeTest.forClass(ItemFrame.class),
                    searchArea,
                    frame -> frame.isAlive()
                            && !frame.isRemoved()
                            && !frame.getItem().isEmpty()
            );
            for (ItemFrame frame : frames) {
                double distance = frame.position().distanceToSqr(center);
                if (distance <= MAX_DISTANCE_SQUARED) {
                    result.add(new Candidate(
                            index,
                            frame.getUUID(),
                            itemId(frame.getItem()),
                            distance,
                            order(target, frame)
                    ));
                }
            }
        }
        return result;
    }

    private static Vec3 center(ContainerTarget target) {
        return new Vec3(
                target.position().x() + 0.5,
                target.position().y() + 0.5,
                target.position().z() + 0.5
        );
    }

    private static String order(ContainerTarget target, ItemFrame frame) {
        return target.position().toString() + ':' + frame.getUUID();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private record Candidate(
            int containerIndex,
            UUID frameId,
            String itemId,
            double distanceSquared,
            String tieBreaker
    ) {
        private static final java.util.Comparator<Candidate> ORDER =
                java.util.Comparator
                        .comparingDouble(Candidate::distanceSquared)
                        .thenComparing(Candidate::tieBreaker);
    }
}
