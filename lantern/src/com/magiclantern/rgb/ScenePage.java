package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 场景模式页：左侧分类导航 + 右侧灯效网格 + 速度 */
public class ScenePage extends Page {

    private static final int COLUMNS = 5;
    /** 自定义渐变分类（排在分类列表最上方） */
    private static final int GROUP_CUSTOM = -1;
    /** 固件双色流动大盘分类（第二项） */
    private static final int GROUP_FLOW = -2;

    private final TextView[] groupChips = new TextView[ModeData.GROUPS.length + 2];
    /** 每个 chip 位置对应的分类值 */
    private int[] navIndices;
    private final java.util.List<TextView> itemViews = new java.util.ArrayList<TextView>();
    private LinearLayout gridColumn;
    private Context ctx;
    private TextView tvSpeed;
    private TextView tvGridHeader;
    private android.widget.SeekBar speedBar;
    private int groupIndex = GROUP_CUSTOM;
    private int selectedIndex = 0;
    /** 当前选中 / 正在播放的自定义渐变名称 */
    private String selectedGradient;
    /** 当前选中的固件渐变命令码（-1 表示无） */
    private int selectedFirmwareCmd = -1;
    /** 当前选中的双色流动效果命令码（-1 表示无） */
    private int selectedFlowCmd = -1;
    /** 搜索框与关键词（输入即时筛选 / 跨分类搜索） */
    private android.widget.EditText searchBox;
    private String searchKeyword = "";
    /** 程序化清空搜索时抑制 TextWatcher 回调，避免递归 */
    private boolean searchInternal;
    /** 双色流动网格的卡片引用（用于局部刷新选中态，避免重建导致滚动位置丢失） */
    private final java.util.List<LinearLayout> flowCells =
            new java.util.ArrayList<LinearLayout>();
    private final java.util.List<int[]> flowCellColors = new java.util.ArrayList<int[]>();
    private final java.util.List<Integer> flowCellCmds = new java.util.ArrayList<Integer>();

    /** 固件内置渐变（设备端执行，关掉 App 也生效） */
    private static final int[] FW_CMDS = {199, 200, 201, 202, 203, 204};
    private static final String[] FW_NAMES = {
            "七色渐变", "红黄交替渐变", "红紫交替渐变", "绿青交替渐变", "绿黄交替渐变", "蓝紫交替渐变"
    };
    private static final int[][] FW_COLORS = {
            {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF},
            {0xFFFF0000, 0xFFFFFF00},
            {0xFFFF0000, 0xFFFF00FF},
            {0xFF00FF00, 0xFF00FFFF},
            {0xFF00FF00, 0xFFFFFF00},
            {0xFF0000FF, 0xFFFF00FF}
    };

    private final android.os.Handler sendHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sendSpeedTask = new Runnable() {
        @Override
        public void run() {
            LedOutput.sendSpeed(activity, Prefs.get(activity).getSpeed());
        }
    };

    public ScenePage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "场景模式";
    }

    @Override
    public String getSubtitle() {
        return "选择灯效并调节速度";
    }

    @Override
    public boolean showBack() {
        return true;
    }

    @Override
    protected View build(Context c) {
        ctx = c;
        LinearLayout root = Ui.row(c);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));
        root.setGravity(Gravity.TOP);

        // ---------------- 左：分类导航 ----------------
        LinearLayout left = Ui.column(c);
        left.setLayoutParams(Ui.lp(dp(140), ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        left.setPadding(0, 0, dp(10), 0);
        left.addView(Ui.sectionHeader(c, "灯效分类"));

        navIndices = new int[ModeData.GROUPS.length + 2];
        navIndices[0] = GROUP_CUSTOM;
        navIndices[1] = GROUP_FLOW;
        for (int i = 0; i < ModeData.GROUPS.length; i++) navIndices[i + 2] = i;

        LinearLayout navColumn = Ui.column(c);
        for (int i = 0; i < navIndices.length; i++) {
            final int index = navIndices[i];
            String label = index == GROUP_CUSTOM ? "自定义"
                    : (index == GROUP_FLOW ? "双色流动" : ModeData.GROUPS[index]);
            TextView chip = Ui.text(c, label, 14, Ui.TEXT_SECONDARY, false);
            chip.setGravity(Gravity.CENTER);
            Res.bg(chip, Res.bg_chip);
            LinearLayout.LayoutParams clp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            clp.bottomMargin = dp(8);
            chip.setLayoutParams(clp);
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectGroup(index);
                }
            });
            groupChips[i] = chip;
            navColumn.addView(chip);
        }
        android.widget.ScrollView navScroll = Ui.scrollWrap(c, navColumn);
        navScroll.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(navScroll);
        root.addView(left);

        // ---------------- 右：灯效网格 + 速度 ----------------
        LinearLayout right = Ui.column(c);
        right.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        right.setPadding(dp(10), 0, 0, 0);

        // 搜索框（输入即时筛选；跨分类搜 模式 / 渐变 / 流水）
        searchBox = new android.widget.EditText(c);
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索模式 / 渐变 / 流水…");
        searchBox.setTextSize(13);
        searchBox.setTextColor(Ui.TEXT_PRIMARY);
        searchBox.setHintTextColor(Ui.TEXT_THIRD);
        searchBox.setBackground(Ui.roundRect(c, 0x14FFFFFF, 12, 0x33FFFFFF));
        searchBox.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams slp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(8);
        searchBox.setLayoutParams(slp);
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int cnt, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int before, int cnt) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (searchInternal) return;
                searchKeyword = s.toString().trim();
                if (searchKeyword.length() == 0) {
                    selectGroup(groupIndex);
                } else {
                    rebuildSearch(searchKeyword);
                }
            }
        });
        right.addView(searchBox);

        tvGridHeader = Ui.sectionHeader(c, "我的渐变");
        right.addView(tvGridHeader);

        LinearLayout card = Ui.card(c);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        gridColumn = Ui.column(c);
        android.widget.ScrollView gridScroll = Ui.scrollWrap(c, gridColumn);
        gridScroll.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        card.addView(gridScroll);
        right.addView(card);

        // 速度
        LinearLayout speedRow = Ui.row(c);
        LinearLayout.LayoutParams srlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp(10);
        speedRow.setLayoutParams(srlp);
        speedRow.addView(Ui.text(c, "速度", 13, Ui.TEXT_SECONDARY, false));
        View spacer = new View(c);
        spacer.setLayoutParams(Ui.lp(0, 1, 1f));
        speedRow.addView(spacer);
        tvSpeed = Ui.text(c, String.valueOf(Prefs.get(c).getSpeed()), 14, Ui.TEXT_PRIMARY, true);
        speedRow.addView(tvSpeed);

        speedBar = Ui.seekBar(c, Prefs.get(c).getSpeed(), 100);
        android.widget.SeekBar sb = speedBar;
        sb.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress,
                                          boolean fromUser) {
                tvSpeed.setText(String.valueOf(progress));
                if (fromUser) {
                    Prefs.get(activity).setSpeed(progress);
                    applySpeedToGradient(progress);
                    sendHandler.removeCallbacks(sendSpeedTask);
                    sendHandler.postDelayed(sendSpeedTask, 120L);
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                Prefs.get(activity).setSpeed(seekBar.getProgress());
                applySpeedToGradient(seekBar.getProgress());
                sendHandler.removeCallbacks(sendSpeedTask);
                LedOutput.sendSpeed(activity, seekBar.getProgress());
            }
        });
        right.addView(speedRow);
        right.addView(sb);
        root.addView(right);

        selectedGradient = GradientPlayer.get(activity).playingName();
        selectGroup(GROUP_CUSTOM);
        return root;
    }

    private void selectGroup(int index) {
        groupIndex = index;
        selectedIndex = 0;
        // 切分类时退出搜索态，避免搜索结果一直盖着
        clearSearchInternal();
        for (int i = 0; i < groupChips.length; i++) {
            TextView chip = groupChips[i];
            if (chip == null) continue;
            boolean sel = navIndices != null && i < navIndices.length && navIndices[i] == index;
            chip.setSelected(sel);
            chip.setTextColor(sel ? 0xFF7B5CFF : Ui.TEXT_SECONDARY);
        }
        if (tvGridHeader != null) {
            tvGridHeader.setText(index == GROUP_CUSTOM ? "我的渐变"
                    : (index == GROUP_FLOW ? "双色流动 · 设备端执行，永久生效" : "选择模式"));
        }
        if (index == GROUP_CUSTOM) {
            rebuildCustomGrid();
        } else if (index == GROUP_FLOW) {
            rebuildFlowGrid();
        } else {
            rebuildGrid();
        }
        syncSpeedFromSelection();
    }

    // ---------------- 双色流动大盘 ----------------

    /** 固件"双色流动"效果：分组的色卡网格，点一下即设备端永久执行 */
    private void rebuildFlowGrid() {
        if (gridColumn == null) return;
        gridColumn.removeAllViews();
        itemViews.clear();
        flowCells.clear();
        flowCellColors.clear();
        flowCellCmds.clear();

        for (int g = 0; g < FlowData.GROUPS.length; g++) {
            FlowData.Group group = FlowData.GROUPS[g];
            TextView header = Ui.text(ctx, group.title, 12, Ui.TEXT_SECONDARY, false);
            LinearLayout.LayoutParams hlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (g > 0) hlp.topMargin = dp(16);
            header.setLayoutParams(hlp);
            gridColumn.addView(header);

            java.util.List<View> cells = new java.util.ArrayList<View>();
            for (int i = 0; i < group.names.length; i++) {
                final int cmd = group.cmds[i];
                final String name = group.names[i];
                LinearLayout cell = makeFlowCell(name, group.colors[i], cmd == selectedFlowCmd);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyFlow(cmd, name);
                    }
                });
                // 长按：立即应用 / 添加到主页常用
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(final View v) {
                        effectMenu(v, name, cmd);
                        return true;
                    }
                });
                flowCells.add(cell);
                flowCellColors.add(group.colors[i]);
                flowCellCmds.add(Integer.valueOf(cmd));
                cells.add(cell);
            }
            layoutCells(cells, gridColumn);
        }

        if (gridColumn.getParent() instanceof View) {
            ((View) gridColumn.getParent()).invalidate();
        }
    }

    private LinearLayout makeFlowCell(String name, int[] colors, boolean selected) {
        LinearLayout cell = Ui.column(ctx);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(14), dp(4), dp(14));
        cell.setBackground(flowCardBg(colors, selected));
        cell.setClickable(true);
        cell.setFocusable(true);
        Ui.addPressEffect(cell);
        TextView tv = Ui.text(ctx, name, 13, 0xFFFFFFFF, true);
        tv.setGravity(Gravity.CENTER);
        tv.setShadowLayer(6f, 0f, 1f, 0xCC000000);
        cell.addView(tv);
        return cell;
    }

    private GradientDrawable flowCardBg(int[] colors, boolean selected) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        d.setCornerRadius(dp(12));
        if (selected) {
            // 白色粗边，彩色卡片上一眼可辨
            d.setStroke(dp(3), 0xFFFFFFFF);
        } else {
            d.setStroke(dp(1), 0x33FFFFFF);
        }
        return d;
    }

    /** 应用固件双色效果：只发一条命令，设备端自己循环（永久生效） */
    private void applyFlow(int cmd, String name) {
        GradientPlayer.get(activity).stop();
        selectedGradient = null;
        selectedFirmwareCmd = -1;
        int old = selectedFlowCmd;
        selectedFlowCmd = cmd;
        LedOutput.sendMode(activity, 0, cmd, Prefs.get(activity).getSpeed());
        // 只刷新旧/新两张卡片，不重建网格（保持滚动位置）
        refreshFlowSelection(old, cmd);
        android.widget.Toast.makeText(activity,
                "已应用：" + name + "（设备端执行，关 App 也生效）",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 局部刷新选中态：只改被选中与取消选中的两张卡片 */
    private void refreshFlowSelection(int oldCmd, int newCmd) {
        for (int i = 0; i < flowCells.size(); i++) {
            int c = flowCellCmds.get(i).intValue();
            if (c == oldCmd || c == newCmd) {
                LinearLayout cell = flowCells.get(i);
                if (cell != null) {
                    cell.setBackground(flowCardBg(flowCellColors.get(i), c == newCmd));
                }
            }
        }
    }

    /** 双色流动 / 固件渐变 长按菜单：立即应用 / 收藏到主页 */
    private void effectMenu(final View anchor, final String name, final int cmd) {
        final boolean isFav = Prefs.get(activity).getFavModes().contains("0," + cmd);
        Ui.showPopupMenu(activity, anchor, name,
                new String[]{"立即应用", isFav ? "从主页移除" : "添加到主页常用"},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                if (isFirmwareCmd(cmd)) {
                                    applyFirmware(cmd);
                                } else {
                                    selectedFirmwareCmd = -1;
                                    applyFlow(cmd, name);
                                }
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                if (isFav) {
                                    Prefs.get(activity).removeFavMode(0, cmd);
                                    android.widget.Toast.makeText(activity, "已从主页移除",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } else {
                                    Prefs.get(activity).addFavMode(0, cmd);
                                    android.widget.Toast.makeText(activity, "已添加到主页常用",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                                refreshAfterEdit();
                            }
                        }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        refreshAfterEdit();
                    }
                });
    }

    /** 是否属于"固件渐变"那 6 个命令码 */
    private boolean isFirmwareCmd(int cmd) {
        for (int c : FW_CMDS) {
            if (c == cmd) return true;
        }
        return false;
    }

    /** 编辑类操作后统一刷新：搜索态刷新结果，否则按当前分类刷新 */
    private void refreshAfterEdit() {
        if (searchKeyword != null && searchKeyword.length() > 0) {
            rebuildSearch(searchKeyword);
        } else if (groupIndex == GROUP_FLOW) {
            rebuildFlowGrid();
        } else if (groupIndex == GROUP_CUSTOM) {
            refreshCustomOrSearch();
        }
    }

    /** 自定义相关操作后刷新：搜索态刷新结果，否则刷新自定义网格 */
    private void refreshCustomOrSearch() {
        if (searchKeyword != null && searchKeyword.length() > 0) {
            rebuildSearch(searchKeyword);
        } else {
            rebuildCustomGrid();
        }
    }

    /** 程序化清空搜索框（抑制回调，避免与 selectGroup 相互递归） */
    private void clearSearchInternal() {
        if (searchKeyword == null || searchKeyword.length() == 0) return;
        searchKeyword = "";
        if (searchBox != null) {
            searchInternal = true;
            searchBox.setText("");
            searchInternal = false;
        }
    }

    // ---------------- 搜索（即时筛选）----------------

    /** 跨分类搜索：自定义渐变 + 双色流动/固件渐变 + 9 类固件模式，点一下立即应用 */
    private void rebuildSearch(String kw) {
        if (gridColumn == null) return;
        String key = kw.toLowerCase();
        gridColumn.removeAllViews();
        itemViews.clear();
        flowCells.clear();
        flowCellColors.clear();
        flowCellCmds.clear();
        if (tvGridHeader != null) tvGridHeader.setText("搜索“" + kw + "”");

        java.util.List<View> cells = new java.util.ArrayList<View>();

        // 1) 自定义渐变
        for (final GradientItem g : Prefs.get(activity).getGradients()) {
            if (g.name == null || !g.name.toLowerCase().contains(key)) continue;
            LinearLayout cell = makeFlowCell(g.name + "（自定义）",
                    new int[]{g.color1, g.color2}, false);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyGradient(g);
                }
            });
            cells.add(cell);
        }

        // 2) 双色流动 / 固件渐变（FlowData 全表）
        for (int gi = 0; gi < FlowData.GROUPS.length; gi++) {
            final FlowData.Group group = FlowData.GROUPS[gi];
            for (int i = 0; i < group.names.length; i++) {
                if (!group.names[i].toLowerCase().contains(key)) continue;
                final int cmd = group.cmds[i];
                final String name = group.names[i];
                final int[] colors = group.colors[i];
                LinearLayout cell = makeFlowCell(name, colors, cmd == selectedFlowCmd);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isFirmwareCmd(cmd)) {
                            applyFirmware(cmd);
                        } else {
                            selectedFirmwareCmd = -1;
                            applyFlow(cmd, name);
                        }
                    }
                });
                cell.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(final View v) {
                        effectMenu(v, name, cmd);
                        return true;
                    }
                });
                flowCells.add(cell);
                flowCellColors.add(colors);
                flowCellCmds.add(Integer.valueOf(cmd));
                cells.add(cell);
            }
        }

        // 3) 9 类固件模式
        for (int g = 0; g < ModeData.GROUPS.length; g++) {
            for (int i = 0; i < ModeData.NAMES[g].length; i++) {
                if (!ModeData.NAMES[g][i].toLowerCase().contains(key)) continue;
                final int group = g;
                final int cmd = ModeData.CMDS[g][i];
                final String name = ModeData.NAMES[g][i];
                TextView item = Ui.text(ctx, name, 13, Ui.TEXT_PRIMARY, false);
                item.setGravity(Gravity.CENTER);
                item.setPadding(dp(4), dp(14), dp(4), dp(14));
                item.setBackground(makeItemBackground(false));
                item.setClickable(true);
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectedFlowCmd = -1;
                        selectedFirmwareCmd = -1;
                        LedOutput.sendMode(activity, group, cmd, Prefs.get(activity).getSpeed());
                        android.widget.Toast.makeText(activity, "已应用：" + name,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                cells.add(item);
            }
        }

        if (cells.isEmpty()) {
            gridColumn.addView(Ui.text(ctx, "没有找到匹配的模式", 13, Ui.TEXT_THIRD, false));
        } else {
            layoutCells(cells, gridColumn);
        }
        if (gridColumn.getParent() instanceof View) {
            ((View) gridColumn.getParent()).invalidate();
        }
    }

    // ---------------- 自定义渐变 ----------------

    /** 打开新建 / 编辑对话框（origin 为 null 表示新建） */
    private void openGradientEditor(final GradientItem origin) {
        GradientDialog.show(activity, origin, new GradientDialog.OnSaved() {
            @Override
            public void onSaved(String oldName, GradientItem item) {
                Prefs.get(activity).saveGradient(oldName, item);
                GradientPlayer.get(activity).replace(oldName, item);
                selectedGradient = item.name;
                rebuildCustomGrid();
                applyGradient(item);
                android.widget.Toast.makeText(activity, "已保存并应用：" + item.name,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 应用自定义渐变（App 端定时插值下发颜色） */
    private void applyGradient(GradientItem item) {
        GradientPlayer.get(activity).play(item);
        // 记录当前渐变：内置到地图后可自动恢复（保活）
        Prefs.get(activity).setLastGradient(item.name);
        selectedGradient = item.name;
        selectedFirmwareCmd = -1;
        selectedFlowCmd = -1;
        Prefs.get(activity).setSpeed(item.speed);
        if (tvSpeed != null) tvSpeed.setText(String.valueOf(item.speed));
        if (speedBar != null) speedBar.setProgress(item.speed);
        refreshCustomOrSearch();
    }

    /** "自定义"分类网格：新建入口 + 已保存的渐变卡片 */
    private void rebuildCustomGrid() {
        if (gridColumn == null) return;
        gridColumn.removeAllViews();
        itemViews.clear();

        java.util.List<View> cells = new java.util.ArrayList<View>();

        // 新建入口
        TextView add = Ui.text(ctx, "+ 新建渐变", 13, 0xFF7B5CFF, true);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(4), dp(16), dp(4), dp(16));
        add.setBackground(makeAddBg());
        add.setClickable(true);
        add.setFocusable(true);
        Ui.addPressEffect(add);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGradientEditor(null);
            }
        });
        cells.add(add);

        final java.util.List<GradientItem> list = Prefs.get(activity).getGradients();
        for (int i = 0; i < list.size(); i++) {
            final GradientItem item = list.get(i);
            final LinearLayout cell = Ui.column(ctx);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(14), dp(4), dp(14));
            cell.setBackground(gradientCardBg(item, item.name.equals(selectedGradient)));
            cell.setClickable(true);
            cell.setFocusable(true);
            Ui.addPressEffect(cell);

            TextView name = Ui.text(ctx, item.name, 13, 0xFFFFFFFF, true);
            name.setGravity(Gravity.CENTER);
            name.setShadowLayer(6f, 0f, 1f, 0xCC000000);
            cell.addView(name);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyGradient(item);
                    android.widget.Toast.makeText(activity, "已应用：" + item.name,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(final View v) {
                    v.setBackground(Ui.activeBackground(activity));
                    Ui.setLongPressActive(v, true);
                    Runnable[] actions = new Runnable[]{
                            new Runnable() {
                                @Override
                                public void run() {
                                    applyGradient(item);
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    openGradientEditor(item);
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    GradientPlayer player = GradientPlayer.get(activity);
                                    if (item.name.equals(player.playingName())) player.stop();
                                    Prefs.get(activity).removeGradient(item.name);
                                    if (item.name.equals(selectedGradient)) selectedGradient = null;
                                    rebuildCustomGrid();
                                    android.widget.Toast.makeText(activity, "已删除：" + item.name,
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            }
                    };
                    Ui.showPopupMenu(activity, v, item.name,
                            new String[]{"立即应用", "编辑", "删除"}, actions,
                            new Runnable() {
                                @Override
                                public void run() {
                                    Ui.setLongPressActive(v, false);
                                    v.setBackground(gradientCardBg(item,
                                            item.name.equals(selectedGradient)));
                                }
                            });
                    return true;
                }
            });
            cells.add(cell);
        }

        TextView myHeader = Ui.text(ctx, "自定义渐变 · 需 App 保持运行（退出即停）",
                12, Ui.TEXT_SECONDARY, false);
        myHeader.setPadding(0, dp(2), 0, dp(6));
        gridColumn.addView(myHeader);
        layoutCells(cells, gridColumn);

        // ---- 固件渐变（设备端执行，关掉 App 也一直跑）----
        TextView fwHeader = Ui.text(ctx, "固件渐变 · 关掉 App 也一直执行", 12, Ui.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams fhlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fhlp.topMargin = dp(20);
        fwHeader.setLayoutParams(fhlp);
        gridColumn.addView(fwHeader);

        java.util.List<View> fwCells = new java.util.ArrayList<View>();
        for (int i = 0; i < FW_CMDS.length; i++) {
            final int cmd = FW_CMDS[i];
            final int ci = i;
            LinearLayout cell = Ui.column(ctx);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(14), dp(4), dp(14));
            cell.setBackground(firmwareCardBg(ci, cmd == selectedFirmwareCmd));
            cell.setClickable(true);
            cell.setFocusable(true);
            Ui.addPressEffect(cell);
            TextView name = Ui.text(ctx, FW_NAMES[i], 13, 0xFFFFFFFF, true);
            name.setGravity(Gravity.CENTER);
            name.setShadowLayer(6f, 0f, 1f, 0xCC000000);
            cell.addView(name);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyFirmware(cmd);
                }
            });
            // 长按：立即应用 / 添加到主页常用
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(final View v) {
                    effectMenu(v, FW_NAMES[ci], cmd);
                    return true;
                }
            });
            fwCells.add(cell);
        }
        layoutCells(fwCells, gridColumn);

        if (gridColumn.getParent() instanceof View) {
            ((View) gridColumn.getParent()).invalidate();
        }
    }

    /** 点击固件渐变：只发一条模式命令，之后由设备自己循环（永久生效） */
    private void applyFirmware(int cmd) {
        GradientPlayer.get(activity).stop();
        selectedGradient = null;
        selectedFlowCmd = -1;
        selectedFirmwareCmd = cmd;
        LedOutput.sendMode(activity, 0, cmd, Prefs.get(activity).getSpeed());
        refreshCustomOrSearch();
        android.widget.Toast.makeText(activity,
                "固件渐变已下发（设备端执行，关掉 App 也生效）",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable firmwareCardBg(int i, boolean selected) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                FW_COLORS[i]);
        d.setCornerRadius(dp(12));
        if (selected) {
            d.setStroke(dp(2), 0xFF4A6CF7);
        } else {
            d.setStroke(dp(1), 0x33FFFFFF);
        }
        return d;
    }

    /** 把一组卡片按 COLUMNS 列排布到容器（最后一行补齐占位，保证换行整齐） */
    private void layoutCells(java.util.List<View> cells, LinearLayout parent) {
        if (parent == null || cells.isEmpty()) return;
        LinearLayout line = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i % COLUMNS == 0) {
                line = Ui.row(ctx);
                LinearLayout.LayoutParams llp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) llp.topMargin = dp(8);
                line.setLayoutParams(llp);
                parent.addView(line);
            }
            View cell = cells.get(i);
            LinearLayout.LayoutParams clp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMargins(dp(4), 0, dp(4), 0);
            cell.setLayoutParams(clp);
            if (line != null) line.addView(cell);
        }
        if (line != null) {
            int filled = cells.size() % COLUMNS;
            if (filled != 0) {
                for (int i = filled; i < COLUMNS; i++) {
                    View ghost = new View(ctx);
                    LinearLayout.LayoutParams glp = Ui.lp(0, 1, 1f);
                    glp.setMargins(dp(4), 0, dp(4), 0);
                    ghost.setLayoutParams(glp);
                    line.addView(ghost);
                }
            }
        }
    }

    private GradientDrawable gradientCardBg(GradientItem item, boolean selected) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{item.color1, item.color2});
        d.setCornerRadius(dp(12));
        if (selected) {
            d.setStroke(dp(2), 0xFF4A6CF7);
        } else {
            d.setStroke(dp(1), 0x33FFFFFF);
        }
        return d;
    }

    private GradientDrawable makeAddBg() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(0x1A4A6CF7);
        d.setStroke(dp(1), 0x884A6CF7);
        return d;
    }

    /** 拖动速度时：同步到当前选中的自定义渐变（保存 + 即时生效） */
    private void applySpeedToGradient(int speed) {
        if (groupIndex != GROUP_CUSTOM || selectedGradient == null) return;
        java.util.List<GradientItem> list = Prefs.get(activity).getGradients();
        for (GradientItem item : list) {
            if (item.name.equals(selectedGradient)) {
                item.speed = speed;
                Prefs.get(activity).setGradients(list);
                GradientPlayer.get(activity).applySpeed(item);
                break;
            }
        }
    }

    /** 切换分类后，让速度条显示当前选中渐变的速度 */
    private void syncSpeedFromSelection() {
        if (groupIndex != GROUP_CUSTOM || selectedGradient == null) return;
        for (GradientItem item : Prefs.get(activity).getGradients()) {
            if (item.name.equals(selectedGradient)) {
                if (tvSpeed != null) tvSpeed.setText(String.valueOf(item.speed));
                if (speedBar != null) speedBar.setProgress(item.speed);
                break;
            }
        }
    }

    private void rebuildGrid() {
        if (gridColumn == null) return;
        gridColumn.removeAllViews();
        itemViews.clear();
        String[] names = ModeData.NAMES[groupIndex];
        LinearLayout line = null;
        for (int i = 0; i < names.length; i++) {
            if (i % COLUMNS == 0) {
                line = Ui.row(ctx);
                LinearLayout.LayoutParams llp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) llp.topMargin = dp(8);
                line.setLayoutParams(llp);
                gridColumn.addView(line);
            }
            final int index = i;
            TextView item = Ui.text(ctx, names[i], 13,
                    i == selectedIndex ? 0xFF7B5CFF : Ui.TEXT_PRIMARY, false);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(4), dp(14), dp(4), dp(14));
            item.setBackground(makeItemBackground(i == selectedIndex));
            LinearLayout.LayoutParams ilp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.setMargins(dp(4), 0, dp(4), 0);
            item.setLayoutParams(ilp);
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 只刷新选中态，不重建列表（重建会销毁正在被点击的按钮，导致后续点击丢失）
                    if (selectedIndex != index) {
                        int old = selectedIndex;
                        selectedIndex = index;
                        refreshItemUi(old);
                        refreshItemUi(index);
                    }
                    LedOutput.sendMode(activity, groupIndex, ModeData.CMDS[groupIndex][index],
                            Prefs.get(activity).getSpeed());
                }
            });
            final int gIndex = groupIndex;
            final int mCmd = ModeData.CMDS[groupIndex][index];
            final String mName = ModeData.nameOf(gIndex, mCmd);
            Ui.addPressEffect(item);
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(final View v) {
                    // 长按：按钮放大并高亮保持，明确指示操作对象（菜单关闭后恢复）
                    v.setBackground(Ui.activeBackground(activity));
                    Ui.setLongPressActive(v, true);
                    final boolean isFav = Prefs.get(activity).getFavModes()
                            .contains(gIndex + "," + mCmd);
                    String[] menuItems = isFav
                            ? new String[]{"立即应用", "从主页移除"}
                            : new String[]{"立即应用", "添加到主页常用"};
                    Runnable[] menuActions = new Runnable[]{
                            new Runnable() {
                                @Override
                                public void run() {
                                    LedOutput.sendMode(activity, gIndex, mCmd,
                                            Prefs.get(activity).getSpeed());
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (isFav) {
                                        Prefs.get(activity).removeFavMode(gIndex, mCmd);
                                    } else {
                                        Prefs.get(activity).addFavMode(gIndex, mCmd);
                                    }
                                    host.refreshPages();
                                    android.widget.Toast.makeText(activity,
                                            isFav ? "已从主页移除" : "已添加到主页常用",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            }
                    };
                    Ui.showPopupMenu(activity, v, mName, menuItems, menuActions,
                            new Runnable() {
                                @Override
                                public void run() {
                                    Ui.setLongPressActive(v, false);
                                    refreshItemUi(index);
                                }
                            });
                    return true;
                }
            });
            itemViews.add(item);
            if (line != null) line.addView(item);
        }
        // 最后一行补齐，保证换行整齐
        if (line != null) {
            int filled = names.length % COLUMNS;
            if (filled != 0) {
                for (int i = filled; i < COLUMNS; i++) {
                    View ghost = new View(ctx);
                    LinearLayout.LayoutParams glp = Ui.lp(0, 1, 1f);
                    glp.setMargins(dp(4), 0, dp(4), 0);
                    ghost.setLayoutParams(glp);
                    line.addView(ghost);
                }
            }
        }
        if (gridColumn.getParent() instanceof View) {
            ((View) gridColumn.getParent()).invalidate();
        }
    }

    /** 仅刷新单个模式项的外观 */
    private void refreshItemUi(int index) {
        if (index < 0 || index >= itemViews.size()) return;
        TextView tv = itemViews.get(index);
        if (tv == null) return;
        tv.setBackground(makeItemBackground(index == selectedIndex));
        tv.setTextColor(index == selectedIndex ? 0xFF7B5CFF : Ui.TEXT_PRIMARY);
    }

    private GradientDrawable makeItemBackground(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        if (selected) {
            d.setColor(0x332F6BFF);
            d.setStroke(dp(1), 0xFF4A6CF7);
        } else {
            d.setColor(Ui.CARD_INNER);
            d.setStroke(dp(1), 0x14FFFFFF);
        }
        return d;
    }

    @Override
    public void onHide() {
        sendHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onShow() {
        if (groupIndex == GROUP_CUSTOM && gridColumn != null) {
            rebuildCustomGrid();
        }
    }

    @Override
    public void refresh() {
        if (tvSpeed != null) {
            tvSpeed.setText(String.valueOf(Prefs.get(activity).getSpeed()));
        }
        // 自定义分类：跟随渐变播放状态刷新选中态
        if (groupIndex == GROUP_CUSTOM && gridColumn != null) {
            String playing = GradientPlayer.get(activity).playingName();
            if (playing == null) {
                if (selectedGradient != null) {
                    selectedGradient = null;
                    rebuildCustomGrid();
                }
            } else if (!playing.equals(selectedGradient)) {
                selectedGradient = playing;
                rebuildCustomGrid();
            }
            gridColumn.invalidate();
        } else if (gridColumn != null && gridColumn.getChildCount() > 0) {
            gridColumn.invalidate();
        }
    }
}
