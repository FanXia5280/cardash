package com.s05.hudtraffic;

/**
 * 手写的 R 常量（替代 aapt 生成的资源表）。
 *
 * <p>内置进 D+ 桌面时构建流程（build_local.py）不碰 resources.arsc / res/，
 * 所以 HUD 源码里 {@code R.id.*} 这些「编译期内联 int」没有资源表可生成。
 * 这里手工定义一份 —— 值只需在进程内唯一，与桌面资源 id 不冲突即可
 * （只用于 {@code View.setId()} / {@code findViewById()}）。
 */
public final class R {

    private R() {
    }

    public static final class id {
        public static final int tvHudStatus = 0x0D000001;
        public static final int tvDisplays = 0x0D000002;
        public static final int tvData = 0x0D000003;
        public static final int swPreviewDemo = 0x0D000004;
        public static final int previewBox = 0x0D000005;
        public static final int swHud = 0x0D000006;
        public static final int swRelaxed = 0x0D000007;
        public static final int swSimulate = 0x0D000008;
        public static final int swMirror = 0x0D000009;
        public static final int tvPosLabel = 0x0D00000A;
        public static final int sbPosX = 0x0D00000B;
        public static final int sbPosY = 0x0D00000C;
        public static final int tvScaleLabel = 0x0D00000D;
        public static final int sbScale = 0x0D00000E;
        public static final int tvPerm = 0x0D00000F;
        public static final int btnPerm = 0x0D000010;
        public static final int tvKeepAlive = 0x0D000011;
        public static final int btnBattery = 0x0D000012;
        public static final int btnCopyLog = 0x0D000013;
        public static final int btnClearLog = 0x0D000014;
        public static final int svLog = 0x0D000015;
        public static final int tvLog = 0x0D000016;
    }
}
