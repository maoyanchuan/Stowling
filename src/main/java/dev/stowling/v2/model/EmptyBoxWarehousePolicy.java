package dev.stowling.v2.model;

/** The warehouse supplies classification boxes and receives surplus empty shells. */
public record EmptyBoxWarehousePolicy(boolean enabled, String warehouseRef,
        boolean retainInCache, Integer cacheLimit) {
    public static final int DEFAULT_CACHE_LIMIT = 9;
    public static final String SETTLE_MODULE = "SETTLE_EMPTY_BOXES";

    public EmptyBoxWarehousePolicy {
        cacheLimit = cacheLimit == null ? DEFAULT_CACHE_LIMIT : cacheLimit;
        if (cacheLimit < 0 || cacheLimit > 3456) {
            throw new IllegalArgumentException("缓存空盒保留数量必须为 0 到 3456 的整数");
        }
        if (warehouseRef == null || warehouseRef.isBlank()) {
            warehouseRef = null;
        } else {
            var ref = ResourceReference.parse(warehouseRef);
            if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION) {
                throw new IllegalArgumentException("空盒仓库必须选择坐标点或容器区域");
            }
            warehouseRef = ref.encode();
        }
        if (enabled && warehouseRef == null) {
            throw new IllegalArgumentException("启用空盒仓库后请选择仓库位置");
        }
    }

    public int retainedLimit() {
        return retainInCache ? cacheLimit : 0;
    }
}
