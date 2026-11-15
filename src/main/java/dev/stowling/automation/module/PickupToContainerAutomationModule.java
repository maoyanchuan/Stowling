package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleDescriptor.Choice;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationModuleStatus;
import dev.stowling.automation.AutomationOperationPort;
import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.CustomPickupRules;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.schedule.StopOutcome;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Collects one bounded pickup round and deposits only the inventory delta.
 */
public final class PickupToContainerAutomationModule implements AutomationModule {
    private static final String PHASE = "phase";
    private static final String PICKUP_KEY = "pickupKey";
    private static final String TRANSFER_KEY = "transferKey";
    private static final String NEXT_INDEX = "nextIndex";
    private static final String BASELINE = "baseline";
    private static final String COLLECTED = "collected";
    private static final String TRANSFERRED = "transferred";
    private static final String NAVIGATION_STARTED = "navigationStarted";
    private static final String PREPARE = "PREPARE";
    private static final String PICKUP = "PICKUP";
    private static final String NAVIGATE = "NAVIGATE";
    private static final String TRANSFER = "TRANSFER";
    private static final String DESTINATION_TYPE = "containerType";

    private static final AutomationModuleDescriptor DESCRIPTOR =
            new AutomationModuleDescriptor(
                    AutomationStepType.PICKUP_TO_CONTAINER,
                    "拾取并存入",
                    "检测命名范围内是否有掉落物；有物品时拾取一轮，"
                            + "然后前往目标容器并只存入本轮新增物品。",
                    List.of(
                            new Parameter(
                                    "coordinateId",
                                    "掉落物范围",
                                    "coordinate",
                                    true
                            ),
                            new Parameter(
                                    "destinationCoordinateId",
                                    "存入位置",
                                    "point-coordinate",
                                    true
                            ),
                            new Parameter(
                                    DESTINATION_TYPE,
                                    "容器类型",
                                    "choice",
                                    false,
                                    List.of(),
                                    containerTypeChoices()
                            ),
                            new Parameter(
                                    "idleSeconds",
                                    "空闲等待秒数",
                                    "nonnegative-integer",
                                    false
                            ),
                            new Parameter(
                                    "waitForItems",
                                    "等待范围内出现物品",
                                    "boolean",
                                    false
                            )
                    )
            );

    private final PickupWorkflowPort pickups;
    private final ItemTransferPort transfers;
    private final InventoryQueryPort inventory;
    private final NavigationPort navigation;
    private final Supplier<StowlingConfig> configSupplier;

    public PickupToContainerAutomationModule(
            PickupWorkflowPort pickups,
            ItemTransferPort transfers,
            InventoryQueryPort inventory,
            NavigationPort navigation,
            Supplier<StowlingConfig> configSupplier
    ) {
        this.pickups = Objects.requireNonNull(pickups, "pickups");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @Override
    public AutomationModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AutomationModuleResult execute(AutomationModuleContext context) {
        return ModuleParameters.guard(() -> executePhase(context));
    }

    @Override
    public StopOutcome stop(AutomationModuleContext context) {
        return switch (phase(context)) {
            case PICKUP -> stopOperation(context, pickups, PICKUP_KEY);
            case TRANSFER -> stopOperation(context, transfers, TRANSFER_KEY);
            case NAVIGATE -> navigation.requestStop();
            default -> StopOutcome.CLEAN;
        };
    }

    @Override
    public StopOutcome pollStop(AutomationModuleContext context) {
        return switch (phase(context)) {
            case PICKUP -> pollOperationStop(context, pickups, PICKUP_KEY);
            case TRANSFER -> pollOperationStop(context, transfers, TRANSFER_KEY);
            case NAVIGATE -> navigation.pollStop();
            default -> StopOutcome.CLEAN;
        };
    }

    private AutomationModuleResult executePhase(AutomationModuleContext context) {
        Map<String, String> state = new LinkedHashMap<>(context.moduleState());
        String phase = state.getOrDefault(PHASE, PREPARE);
        return switch (phase) {
            case PREPARE -> prepare(context, state);
            case PICKUP -> pickup(context, state);
            case NAVIGATE -> navigate(context, state);
            case TRANSFER -> transfer(context, state);
            default -> AutomationModuleResult.failed("未知拾取存入阶段: " + phase);
        };
    }

    private AutomationModuleResult prepare(
            AutomationModuleContext context,
            Map<String, String> state
    ) {
        Destination destination = destination(context);
        PlayerInventorySnapshot snapshot = inventory.snapshot();
        state.put(BASELINE, encodeCounts(snapshot.itemCounts()));
        state.put(PHASE, PICKUP);
        return running(state, "正在检测 " + destination.pickup().name());
    }

    private AutomationModuleResult pickup(
            AutomationModuleContext context,
            Map<String, String> state
    ) {
        Destination destination = destination(context);
        String operationKey = state.get(PICKUP_KEY);
        AutomationOperationResult result;
        if (operationKey == null) {
            operationKey = OperationKeys.forStep(context) + ":pickup";
            state.put(PICKUP_KEY, operationKey);
            result = pickups.start(operationKey, pickupRequest(context, destination));
        } else if (context.recovered()) {
            result = pickups.reconcile(operationKey);
        } else {
            result = pickups.poll(operationKey);
        }
        return switch (result.status()) {
            case RUNNING -> running(state, result.message().orElse(""));
            case PAUSED -> AutomationModuleResult.paused(
                    result.message().orElse("拾取已暂停")
            );
            case FAILED -> AutomationModuleResult.failed(
                    result.message().orElse("拾取失败")
            );
            case SUCCEEDED -> finishPickup(context, state);
        };
    }

    private AutomationModuleResult finishPickup(
            AutomationModuleContext context,
            Map<String, String> state
    ) {
        Map<String, Integer> baseline = decodeCounts(state.get(BASELINE));
        Map<String, Integer> collected = positiveDelta(
                baseline,
                inventory.snapshot().itemCounts()
        );
        state.put(COLLECTED, encodeCounts(collected));
        state.remove(PICKUP_KEY);
        if (collected.isEmpty()) {
            return AutomationModuleResult.succeeded(
                    state,
                    "PICKUP_TO_CONTAINER:EMPTY"
            );
        }
        state.put(PHASE, NAVIGATE);
        state.put(NAVIGATION_STARTED, "true");
        NavigationStatus status = navigation.start(destination(context).container().position());
        return navigationResult(state, status);
    }

    private AutomationModuleResult navigate(
            AutomationModuleContext context,
            Map<String, String> state
    ) {
        Destination destination = destination(context);
        NavigationStatus status = !context.recovered()
                && "true".equals(state.get(NAVIGATION_STARTED))
                ? navigation.status(destination.container().position())
                : navigation.start(destination.container().position());
        state.put(NAVIGATION_STARTED, "true");
        if (status == NavigationStatus.ARRIVED) {
            state.remove(NAVIGATION_STARTED);
            state.put(PHASE, TRANSFER);
            state.put(NEXT_INDEX, "0");
            state.remove(TRANSFER_KEY);
        }
        return navigationResult(state, status);
    }

    private AutomationModuleResult transfer(
            AutomationModuleContext context,
            Map<String, String> state
    ) {
        Destination destination = destination(context);
        List<Map.Entry<String, Integer>> items = sortedEntries(
                decodeCounts(state.get(COLLECTED))
        );
        int index = parseIndex(state.get(NEXT_INDEX));
        if (index >= items.size()) {
            return AutomationModuleResult.succeeded(
                    state,
                    "PICKUP_TO_CONTAINER:COMPLETE:"
                            + state.getOrDefault(TRANSFERRED, "0")
            );
        }
        Map.Entry<String, Integer> item = items.get(index);
        int available = availableForTransfer(state, item.getKey());
        if (available < 1) {
            state.put(NEXT_INDEX, String.valueOf(index + 1));
            state.remove(TRANSFER_KEY);
            return running(state, "本轮物品已不在背包，跳过 " + item.getKey());
        }
        int count = Math.min(item.getValue(), available);
        String operationKey = state.get(TRANSFER_KEY);
        AutomationOperationResult result;
        if (operationKey == null) {
            operationKey = OperationKeys.forStep(context) + ":put:" + index;
            state.put(TRANSFER_KEY, operationKey);
            result = transfers.start(
                    operationKey,
                    transferRequest(destination, item.getKey(), count)
            );
        } else if (context.recovered()) {
            result = transfers.reconcile(operationKey);
        } else {
            result = transfers.poll(operationKey);
        }
        return switch (result.status()) {
            case RUNNING -> running(state, result.message().orElse(""));
            case PAUSED -> AutomationModuleResult.paused(
                    result.message().orElse("目标容器已满或无法接收物品")
            );
            case FAILED -> AutomationModuleResult.failed(
                    result.message().orElse("存入物品失败")
            );
            case SUCCEEDED -> {
                int transferred = Integer.parseInt(
                        state.getOrDefault(TRANSFERRED, "0")
                ) + count;
                state.put(TRANSFERRED, String.valueOf(transferred));
                state.put(NEXT_INDEX, String.valueOf(index + 1));
                state.remove(TRANSFER_KEY);
                yield running(state, "已存入 " + item.getKey() + " × " + count);
            }
        };
    }

    private PickupWorkflowPort.Request pickupRequest(
            AutomationModuleContext context,
            Destination destination
    ) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("coordinateId", destination.pickup().id());
        parameters.put("idleSeconds", context.step().parameters()
                .getOrDefault("idleSeconds", "2"));
        CustomPickupRules rules = CustomPickupRules.fromParameters(parameters)
                .orElseThrow(() -> new IllegalArgumentException(
                        "pickup-to-container requires a pickup coordinate"
                ));
        boolean waitForItems = ModuleParameters.optional(
                context.step(),
                "waitForItems"
        ).map(PickupToContainerAutomationModule::parseBoolean).orElse(false);
        return PickupWorkflowPort.Request.custom(rules, waitForItems);
    }

    private ItemTransferPort.Request transferRequest(
            Destination destination,
            String itemId,
            int count
    ) {
        return new ItemTransferPort.Request(
                ItemTransferPort.Mode.PUT,
                "PLAYER",
                destination.container().id(),
                itemId,
                count,
                Optional.empty(),
                Optional.of(destination.container())
        );
    }

    private Destination destination(AutomationModuleContext context) {
        StowlingConfig config = Objects.requireNonNull(configSupplier.get(), "config");
        String pickupId = ModuleParameters.required(context.step(), "coordinateId");
        String destinationId = ModuleParameters.required(
                context.step(),
                "destinationCoordinateId"
        );
        NamedCoordinate pickup = TaskContainerParameters.coordinate(
                () -> config,
                pickupId
        );
        NamedCoordinate stored = TaskContainerParameters.coordinate(
                () -> config,
                destinationId
        );
        if (pickup.type() == CoordinateType.POINT) {
            throw new IllegalArgumentException("掉落物范围不能是单点坐标");
        }
        if (stored.type() != CoordinateType.POINT) {
            throw new IllegalArgumentException("存入位置必须是单点坐标");
        }
        if (!pickup.dimension().equals(stored.dimension())) {
            throw new IllegalArgumentException("掉落物和存入位置必须位于同一维度");
        }
        ContainerType type = TaskContainerParameters.containerType(
                context.step(),
                DESTINATION_TYPE
        );
        ContainerRef container = new ContainerRef(
                stored.id(),
                stored.dimension(),
                stored.position(),
                type
        );
        return new Destination(pickup, container);
    }

    private int availableForTransfer(Map<String, String> state, String itemId) {
        Map<String, Integer> baseline = decodeCounts(state.get(BASELINE));
        Map<String, Integer> current = inventory.snapshot().itemCounts();
        return Math.max(
                0,
                current.getOrDefault(itemId, 0)
                        - baseline.getOrDefault(itemId, 0)
        );
    }

    private AutomationModuleResult navigationResult(
            Map<String, String> state,
            NavigationStatus status
    ) {
        return switch (status) {
            case ARRIVED -> running(state, "");
            case NAVIGATING -> running(state, "正在前往目标容器");
            case FAILED -> AutomationModuleResult.paused("无法前往目标容器");
        };
    }

    private static AutomationModuleResult running(
            Map<String, String> state,
            String message
    ) {
        return new AutomationModuleResult(
                AutomationModuleStatus.RUNNING,
                state,
                Optional.empty(),
                message.isBlank() ? Optional.empty() : Optional.of(message)
        );
    }

    private static boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "waitForItems must be true or false"
            );
        };
    }

    private static String phase(AutomationModuleContext context) {
        return context.moduleState().getOrDefault(PHASE, PREPARE);
    }

    private static <Request> StopOutcome stopOperation(
            AutomationModuleContext context,
            AutomationOperationPort<Request> port,
            String keyName
    ) {
        String operationKey = context.moduleState().get(keyName);
        if (operationKey == null || taskId(operationKey) == null) {
            return StopOutcome.CLEAN;
        }
        return port.requestStop(taskId(operationKey));
    }

    private static <Request> StopOutcome pollOperationStop(
            AutomationModuleContext context,
            AutomationOperationPort<Request> port,
            String keyName
    ) {
        String operationKey = context.moduleState().get(keyName);
        if (operationKey == null || taskId(operationKey) == null) {
            return StopOutcome.CLEAN;
        }
        return port.pollStop(taskId(operationKey));
    }

    private static UUID taskId(String operationKey) {
        int separator = operationKey.indexOf(':');
        String value = separator < 0 ? operationKey : operationKey.substring(0, separator);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static int parseIndex(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid transfer index");
        }
    }

    private static Map<String, Integer> positiveDelta(
            Map<String, Integer> baseline,
            Map<String, Integer> current
    ) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            int difference = entry.getValue() - baseline.getOrDefault(entry.getKey(), 0);
            if (difference > 0) {
                result.put(entry.getKey(), difference);
            }
        }
        return Map.copyOf(result);
    }

    private static String encodeCounts(Map<String, Integer> counts) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Integer> entry : sortedEntries(counts)) {
            if (!encoded.isEmpty()) {
                encoded.append(',');
            }
            encoded.append(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8)
            ));
            encoded.append('=').append(entry.getValue());
        }
        return encoded.toString();
    }

    private static Map<String, Integer> decodeCounts(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String entry : encoded.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("invalid inventory count state");
            }
            String itemId = new String(
                    Base64.getUrlDecoder().decode(entry.substring(0, separator)),
                    StandardCharsets.UTF_8
            );
            result.put(itemId, Integer.parseInt(entry.substring(separator + 1)));
        }
        return Map.copyOf(result);
    }

    private static List<Map.Entry<String, Integer>> sortedEntries(
            Map<String, Integer> counts
    ) {
        return new ArrayList<>(counts.entrySet()).stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }

    private static List<Choice> containerTypeChoices() {
        return List.of(
                new Choice(ContainerType.CHEST.name(), "箱子"),
                new Choice(ContainerType.TRAPPED_CHEST.name(), "陷阱箱"),
                new Choice(ContainerType.BARREL.name(), "木桶"),
                new Choice(ContainerType.SHULKER_BOX.name(), "潜影盒"),
                new Choice(ContainerType.HOPPER.name(), "漏斗"),
                new Choice(ContainerType.OTHER.name(), "其他")
        );
    }

    private record Destination(NamedCoordinate pickup, ContainerRef container) {
    }
}
