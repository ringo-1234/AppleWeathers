package jp.apple.aw.weather;

public enum WindDirectionType {
    /* 北 */
    N("北", 0.0),
    /* 北東 */
    NE("北東", 45.0),
    /* 東 */
    E("東", 90.0),
    /* 南東 */
    SE("南東", 135.0),
    /* 南 */
    S("南", 180.0),
    /* 南西 */
    SW("南西", 225.0),
    /* 西 */
    W("西", 270.0),
    /* 北西 */
    NW("北西", 315.0);

    private final String label;
    private final double degree;

    WindDirectionType(String label, double degree) {
        this.label = label;
        this.degree = degree;
    }

    public String getLabel() {
        return label;
    }

    public double getDegree() {
        return degree;
    }
    
    public static WindDirectionType fromDegree(double degree) {
        double normalized = (degree % 360 + 360) % 360;

        if (normalized >= 337.5 || normalized < 22.5) return N;
        if (normalized >= 22.5  && normalized < 67.5) return NE;
        if (normalized >= 67.5  && normalized < 112.5) return E;
        if (normalized >= 112.5 && normalized < 157.5) return SE;
        if (normalized >= 157.5 && normalized < 202.5) return S;
        if (normalized >= 202.5 && normalized < 247.5) return SW;
        if (normalized >= 247.5 && normalized < 292.5) return W;
        return NW;
    }
}
