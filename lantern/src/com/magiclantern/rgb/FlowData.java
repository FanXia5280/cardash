package com.magiclantern.rgb;

/**
 * 固件"双色流动"效果表（命令码与原版一致）。
 *
 * 这些是灯带芯片内置的效果：点一次只发一条命令，之后由设备自己循环，
 * 关掉 App、断开蓝牙、重启手机都照常执行。
 */
public class FlowData {

    public static final int RED = 0xFFFF0000;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int GREEN = 0xFF00FF00;
    public static final int CYAN = 0xFF00FFFF;
    public static final int BLUE = 0xFF0000FF;
    public static final int PURPLE = 0xFFFF00FF;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int BLACK = 0xFF000000;

    private static final int[] RAINBOW = {RED, YELLOW, GREEN, CYAN, BLUE, PURPLE};

    public static class Group {
        public final String title;
        public final String[] names;
        public final int[] cmds;
        public final int[][] colors;

        Group(String title, String[] names, int[] cmds, int[][] colors) {
            this.title = title;
            this.names = names;
            this.cmds = cmds;
            this.colors = colors;
        }
    }

    public static final Group[] GROUPS = {

            new Group("交替渐变 · 两色来回渐变",
                    new String[]{"七色渐变", "红黄交替渐变", "红紫交替渐变",
                            "绿青交替渐变", "绿黄交替渐变", "蓝紫交替渐变"},
                    new int[]{199, 200, 201, 202, 203, 204},
                    new int[][]{
                            RAINBOW,
                            {RED, YELLOW},
                            {RED, PURPLE},
                            {GREEN, CYAN},
                            {GREEN, YELLOW},
                            {BLUE, PURPLE},
                    }),

            new Group("双色流水 · 正向 / 反向",
                    new String[]{"正向红绿流水", "反向红绿流水", "正向绿蓝流水", "反向绿蓝流水",
                            "正向黄蓝流水", "反向黄蓝流水", "正向黄青流水", "反向黄青流水",
                            "正向青紫流水", "反向青紫流水", "正向黑白流水", "反向黑白流水"},
                    new int[]{45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56},
                    new int[][]{
                            {RED, GREEN}, {RED, GREEN},
                            {GREEN, BLUE}, {GREEN, BLUE},
                            {YELLOW, BLUE}, {YELLOW, BLUE},
                            {YELLOW, CYAN}, {YELLOW, CYAN},
                            {CYAN, PURPLE}, {CYAN, PURPLE},
                            {BLACK, WHITE}, {BLACK, WHITE},
                    }),

            new Group("多色流水 · 正向 / 反向",
                    new String[]{"正向七彩流水", "反向七彩流水", "正向蓝绿红流水",
                            "反向红绿蓝流水", "正向紫青黄流水", "反向黄青紫流水"},
                    new int[]{39, 40, 41, 42, 43, 44},
                    new int[][]{
                            RAINBOW,
                            RAINBOW,
                            {BLUE, GREEN, RED},
                            {RED, GREEN, BLUE},
                            {PURPLE, CYAN, YELLOW},
                            {YELLOW, CYAN, PURPLE},
                    }),

            new Group("双色流动 · 正向 / 反向",
                    new String[]{"正向白红白流动", "反向白红白流动", "正向白绿白流动", "反向白绿白流动",
                            "正向白蓝白流动", "反向白蓝白流动", "正向白黄白流动", "反向白黄白流动",
                            "正向白青白流动", "反向白青白流动", "正向白紫白流动", "反向白紫白流动",
                            "正向红白红流动", "反向红白红流动", "正向绿白绿流动", "反向绿白绿流动",
                            "正向蓝白蓝流动", "反向蓝白蓝流动", "正向黄白黄流动", "反向黄白黄流动",
                            "正向青白青流动", "反向青白青流动", "正向紫白紫流动", "反向紫白紫流动"},
                    new int[]{143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154,
                            155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166},
                    new int[][]{
                            {WHITE, RED}, {WHITE, RED},
                            {WHITE, GREEN}, {WHITE, GREEN},
                            {WHITE, BLUE}, {WHITE, BLUE},
                            {WHITE, YELLOW}, {WHITE, YELLOW},
                            {WHITE, CYAN}, {WHITE, CYAN},
                            {WHITE, PURPLE}, {WHITE, PURPLE},
                            {RED, WHITE}, {RED, WHITE},
                            {GREEN, WHITE}, {GREEN, WHITE},
                            {BLUE, WHITE}, {BLUE, WHITE},
                            {YELLOW, WHITE}, {YELLOW, WHITE},
                            {CYAN, WHITE}, {CYAN, WHITE},
                            {PURPLE, WHITE}, {PURPLE, WHITE},
                    }),

            new Group("单色拖尾 · 正向 / 反向（黑底）",
                    new String[]{"正向七彩拖尾", "反向七彩拖尾", "正向红色拖尾", "反向红色拖尾",
                            "正向绿色拖尾", "反向绿色拖尾", "正向蓝色拖尾", "反向蓝色拖尾",
                            "正向黄色拖尾", "反向黄色拖尾", "正向青色拖尾", "反向青色拖尾",
                            "正向紫色拖尾", "反向紫色拖尾", "正向白色拖尾", "反向白色拖尾"},
                    new int[]{23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38},
                    new int[][]{
                            RAINBOW, RAINBOW,
                            {RED, BLACK}, {RED, BLACK},
                            {GREEN, BLACK}, {GREEN, BLACK},
                            {BLUE, BLACK}, {BLUE, BLACK},
                            {YELLOW, BLACK}, {YELLOW, BLACK},
                            {CYAN, BLACK}, {CYAN, BLACK},
                            {PURPLE, BLACK}, {PURPLE, BLACK},
                            {WHITE, BLACK}, {WHITE, BLACK},
                    }),

            new Group("开合 · 闭幕 / 拉幕",
                    new String[]{"七彩闭幕", "七彩拉幕", "红绿蓝闭幕", "红绿蓝拉幕",
                            "黄青紫闭幕", "黄青紫拉幕", "红色闭幕", "红色拉幕",
                            "绿色闭幕", "绿色拉幕", "蓝色闭幕", "蓝色拉幕",
                            "黄色闭幕", "黄色拉幕", "青色闭幕", "青色拉幕",
                            "紫色闭幕", "紫色拉幕", "白色闭幕", "白色拉幕"},
                    new int[]{57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
                            71, 72, 73, 74, 75, 76},
                    new int[][]{
                            RAINBOW, RAINBOW,
                            {RED, GREEN, BLUE}, {RED, GREEN, BLUE},
                            {YELLOW, CYAN, PURPLE}, {YELLOW, CYAN, PURPLE},
                            {RED, BLACK}, {RED, BLACK},
                            {GREEN, BLACK}, {GREEN, BLACK},
                            {BLUE, BLACK}, {BLUE, BLACK},
                            {YELLOW, BLACK}, {YELLOW, BLACK},
                            {CYAN, BLACK}, {CYAN, BLACK},
                            {PURPLE, BLACK}, {PURPLE, BLACK},
                            {WHITE, BLACK}, {WHITE, BLACK},
                    }),
    };
}
