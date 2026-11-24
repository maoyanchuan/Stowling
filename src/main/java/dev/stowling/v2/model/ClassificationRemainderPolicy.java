package dev.stowling.v2.model;

/** Task-local destinations for items left after all selected category zones have run. */
public record ClassificationRemainderPolicy(boolean unclassifiableEnabled,
        String unclassifiableDestinationRef, boolean overflowEnabled, String overflowDestinationRef) {
    public static final String MODULE = "ROUTE_CLASSIFICATION_REMAINDERS";
    public static final String KIND = "remainderKind";
    public static final String UNCLASSIFIABLE = "UNCLASSIFIABLE";
    public static final String OVERFLOW = "OVERFLOW";
    public static final ClassificationRemainderPolicy DEFAULT =
            new ClassificationRemainderPolicy(false, null, false, null);

    public ClassificationRemainderPolicy {
        unclassifiableDestinationRef = destination(unclassifiableDestinationRef,
                unclassifiableEnabled, "不可分类");
        overflowDestinationRef = destination(overflowDestinationRef, overflowEnabled, "溢出");
    }

    private static String destination(String value, boolean enabled, String label) {
        if (value == null || value.isBlank()) {
            if (enabled) throw new IllegalArgumentException("请选择" + label + "存放位置");
            return null;
        }
        var ref = ResourceReference.parse(value);
        if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION
                && ref.type() != ResourceType.WAREHOUSE) {
            throw new IllegalArgumentException(label + "存放位置必须引用坐标点、区域或仓库");
        }
        return ref.encode();
    }

    public void requireSeparateSource(String source) {
        if (source.equals(unclassifiableDestinationRef) || source.equals(overflowDestinationRef)) {
            throw new IllegalArgumentException("不可分类和溢出存放位置必须与物品来源分开");
        }
    }
}
