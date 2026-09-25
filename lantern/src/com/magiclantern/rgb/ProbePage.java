package com.magiclantern.rgb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议探测页（进阶）：连接真灯后逐个试候选命令，
 * 寻找"设备端按自定义颜色跑渐变"的写法。
 *
 * 背景：固件自带渐变（199-204）颜色写死，自定义颜色目前只能由 App 端插值下发。
 * 已知颜色帧结构 7E 07 05 03 R G B <标记> EF（10=静态，20=律动），
 * 本页试探其它标记与变体，若某个候选让灯按我们给的颜色跑渐变，
 * 就能做成"设置一次、关掉 App 也生效"。
 */
public class ProbePage extends Page {

    private static final long RESEND_DELAY = 1200L;
    private static final long NEXT_DELAY = 4500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private List<Candidate> candidates;
    private int index;
    private boolean running;

    private TextView tvIndex;
    private TextView tvLabel;
    private TextView tvHex;
    private TextView tvStatus;
    private TextView tvFound;
    private TextView btnStart;

    public ProbePage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "协议探测";
    }

    @Override
    public String getSubtitle() {
        return "寻找设备端自定义渐变命令";
    }

    @Override
    public boolean showBack() {
        return true;
    }

    private static class Candidate {
        final String label;
        final byte[][] frames;

        Candidate(String label, byte[][] frames) {
            this.label = label;
            this.frames = frames;
        }
    }

    // ---------------- 候选命令 ----------------

    private static List<Candidate> buildCandidates() {
        List<Candidate> list = new ArrayList<Candidate>();

        // 组合序列：先设两个静态色，再切固件渐变，观察渐变用的是"我们设的色"还是"固件固定色"
        list.add(new Candidate("组合序列 · 设红、设蓝，再跑 七色渐变(199)（看是红蓝还是彩虹）",
                new byte[][]{LedCommand.color(0xFF, 0x00, 0x00),
                        LedCommand.color(0x00, 0x00, 0xFF), LedCommand.mode(199)}));
        list.add(new Candidate("组合序列 · 设蓝、设紫，再跑 红黄渐变(200)（看是蓝紫还是红黄）",
                new byte[][]{LedCommand.color(0x00, 0x00, 0xFF),
                        LedCommand.color(0xFF, 0x00, 0xFF), LedCommand.mode(200)}));
        list.add(new Candidate("组合序列 · 设红、设黄，再跑 蓝紫渐变(204)（看是红黄还是蓝紫）",
                new byte[][]{LedCommand.color(0xFF, 0x00, 0x00),
                        LedCommand.color(0xFF, 0xFF, 0x00), LedCommand.mode(204)}));
        list.add(new Candidate("组合序列 · 设绿、设青，再跑 红紫渐变(201)（看是绿青还是红紫）",
                new byte[][]{LedCommand.color(0x00, 0xFF, 0x00),
                        LedCommand.color(0x00, 0xFF, 0xFF), LedCommand.mode(201)}));
        list.add(new Candidate("组合序列 · 设红、设蓝，再跑 静态红(05 03 10)",
                new byte[][]{LedCommand.color(0xFF, 0x00, 0x00),
                        LedCommand.color(0x00, 0x00, 0xFF),
                        LedCommand.color(0xFF, 0x00, 0x00)}));

        return list;
    }

    private static String hex(int v) {
        String s = Integer.toHexString(v & 0xFF).toUpperCase();
        return s.length() < 2 ? "0" + s : s;
    }

    private static String hex(byte[] f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hex(f[i]));
        }
        return sb.toString();
    }

    // ---------------- 界面 ----------------

    @Override
    protected View build(Context c) {
        candidates = buildCandidates();
        index = 0;

        LinearLayout root = Ui.row(c);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));
        root.setGravity(Gravity.TOP);

        // 左栏：说明
        LinearLayout left = Ui.column(c);
        left.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f));
        left.setPadding(0, 0, dp(9), 0);
        left.setGravity(Gravity.CENTER_HORIZONTAL);

        View topSpace = new View(c);
        topSpace.setLayoutParams(Ui.lp(1, 0, 0.4f));
        left.addView(topSpace);
        left.addView(Ui.iconCircle(c, Res.grad_green, Res.ic_scene, 82, 21));
        TextView tvTitle = Ui.text(c, "协议探测", 22, Ui.TEXT_PRIMARY, true);
        LinearLayout.LayoutParams tlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        tvTitle.setLayoutParams(tlp);
        left.addView(tvTitle);

        String guide = "用法：\n"
                + "1. 先在主页连接好灯带\n"
                + "2. 点「开始探测」，会每 4.5 秒换一个候选命令\n"
                + "3. 盯着灯看：\n"
                + "   · 出现两色来回渐变 / 呼吸 → 就是它！\n"
                + "   · 只是变纯色、无变化 → 不是\n"
                + "4. 有反应时立刻点「就是它，记录」\n\n"
                + "— 判定测试（最重要）—\n"
                + "点下面 1 / 2 / 3 三个按钮，每次看灯：\n"
                + "1：设蓝+紫 → 跑红黄渐变\n"
                + "   显示 蓝紫 = 支持｜显示 红黄 = 不支持\n"
                + "2：设红+黄 → 跑蓝紫渐变\n"
                + "   显示 红黄 = 支持｜显示 蓝紫 = 不支持\n"
                + "3：设绿+青 → 跑红紫渐变\n"
                + "   显示 绿青 = 支持｜显示 红紫 = 不支持\n\n"
                + "组合序列自动探测里，看到\u201c变色\u201d不算成功\n"
                + "（设色+切模式本来就会变），以判定测试为准。\n\n"
                + "注：命令只改运行状态，若灯异常，\n"
                + "断电重新上电即可恢复。";
        TextView tvGuide = Ui.text(c, guide, 12, Ui.TEXT_SECONDARY, false);
        tvGuide.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams glp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(12);
        tvGuide.setLayoutParams(glp);
        left.addView(tvGuide);

        // 右栏：候选 + 控制
        LinearLayout right = Ui.column(c);
        right.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
        right.setPadding(dp(9), 0, 0, 0);

        LinearLayout holder = Ui.column(c);
        holder.setPadding(0, dp(6), dp(2), 0);

        LinearLayout card = Ui.card(c);
        tvIndex = Ui.text(c, "1 / " + candidates.size(), 13, 0xFF7B5CFF, true);
        card.addView(tvIndex);

        tvLabel = Ui.text(c, "", 15, Ui.TEXT_PRIMARY, true);
        LinearLayout.LayoutParams llp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        tvLabel.setLayoutParams(llp);
        card.addView(tvLabel);

        tvHex = Ui.text(c, "", 13, 0xFF8FB0FF, false);
        LinearLayout.LayoutParams hlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        tvHex.setLayoutParams(hlp);
        tvHex.setBackground(Ui.roundRect(c, 0xFF0F131C, 10, 0x1FFFFFFF));
        tvHex.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(tvHex);

        tvStatus = Ui.text(c, "尚未开始", 12, Ui.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams slp2 = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp2.topMargin = dp(10);
        tvStatus.setLayoutParams(slp2);
        card.addView(tvStatus);

        // 控制按钮
        LinearLayout btnRow1 = Ui.row(c);
        LinearLayout.LayoutParams brlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(12);
        btnRow1.setLayoutParams(brlp);
        TextView btnPrev = actionButton(c, "上一个", false);
        btnStart = actionButton(c, "开始探测", true);
        TextView btnNext = actionButton(c, "下一个", false);
        btnRow1.addView(btnPrev, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams midLp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        midLp.leftMargin = dp(8);
        midLp.rightMargin = dp(8);
        btnRow1.addView(btnStart, midLp);
        btnRow1.addView(btnNext, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(btnRow1);

        // 精确测试：设的颜色与目标模式固定色"完全相反"，一眼可辨
        final PreciseTest[] tests = new PreciseTest[]{
                // 设 蓝+紫 → 跑 红黄渐变：显示蓝紫=用了我们的色；显示红黄=固件固定色
                new PreciseTest("1", 0xFF0000FF, 0xFFFF00FF, "蓝", "紫", 200,
                        "红黄交替渐变(200)", "蓝紫", "红黄"),
                // 设 红+黄 → 跑 蓝紫渐变：显示红黄=用了我们的色；显示蓝紫=固件固定色
                new PreciseTest("2", 0xFFFF0000, 0xFFFFFF00, "红", "黄", 204,
                        "蓝紫交替渐变(204)", "红黄", "蓝紫"),
                // 设 绿+青 → 跑 红紫渐变：显示绿青=用了我们的色；显示红紫=固件固定色
                new PreciseTest("3", 0xFF00FF00, 0xFF00FFFF, "绿", "青", 201,
                        "红紫交替渐变(201)", "绿青", "红紫"),
        };
        for (int i = 0; i < tests.length; i++) {
            final PreciseTest t = tests[i];
            TextView btn = actionButton(c, "判定测试 " + t.tag + "：设 " + t.n1 + "+" + t.n2
                    + " → 跑 " + t.modeName, false);
            LinearLayout.LayoutParams plp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(i == 0 ? 8 : 6);
            btn.setLayoutParams(plp);
            card.addView(btn);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopLoop();
                    sendPreciseTest(t);
                }
            });
        }

        TextView btnFound = actionButton(c, "就是它，记录当前命令", true);
        LinearLayout.LayoutParams flp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        btnFound.setLayoutParams(flp);
        card.addView(btnFound);

        tvFound = Ui.text(c, "", 13, 0xFF4ADE80, false);
        LinearLayout.LayoutParams flp2 = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp2.topMargin = dp(10);
        tvFound.setLayoutParams(flp2);
        tvFound.setBackground(Ui.roundRect(c, 0x1422C55E, 10, 0x6622C55E));
        tvFound.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvFound.setLineSpacing(dp(3), 1f);
        card.addView(tvFound);

        holder.addView(card);
        right.addView(Ui.scrollWrap(c, holder));

        root.addView(left);
        root.addView(right);

        // 事件
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    stopLoop();
                } else {
                    startLoop();
                }
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveTo(index - 1);
                sendCurrent();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveTo(index + 1);
                sendCurrent();
            }
        });
        btnFound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopLoop();
                Candidate cc = candidates.get(index);
                StringBuilder sb = new StringBuilder();
                sb.append("候选 ").append(index + 1).append('/').append(candidates.size())
                        .append('\n').append(cc.label).append('\n').append("命令: ");
                for (int i = 0; i < cc.frames.length; i++) {
                    if (i > 0) sb.append("  然后  ");
                    sb.append(hex(cc.frames[i]));
                }
                Prefs.get(activity).setProbeResult(sb.toString());
                updateFound();
                Toast.makeText(activity,
                        "已记录！把下面绿色框里的内容拍照发我即可", Toast.LENGTH_LONG).show();
            }
        });

        updateUi();
        updateFound();
        return root;
    }

    private TextView actionButton(Context c, String text, boolean primary) {
        TextView tv = Ui.text(c, text, 14, primary ? 0xFFFFFFFF : Ui.TEXT_SECONDARY, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(12), dp(10), dp(12));
        tv.setBackground(primary
                ? Ui.roundRect(c, 0xFF4A6CF7, 14, null)
                : Ui.roundRect(c, 0x1AFFFFFF, 14, null));
        tv.setClickable(true);
        tv.setFocusable(true);
        Ui.addPressEffect(tv);
        return tv;
    }

    // ---------------- 逻辑 ----------------

    /** 一项精确测试：先设 c1/c2 两个颜色，再切到 mode；用"相反色"判断固件是否采用用户色 */
    private static class PreciseTest {
        final String tag;
        final int c1, c2;
        final String n1, n2;
        final int mode;
        final String modeName;
        /** 灯显示 supportColor → 用了我们设的颜色；显示 rejectColor → 固件固定色 */
        final String supportColor, rejectColor;

        PreciseTest(String tag, int c1, int c2, String n1, String n2, int mode,
                    String modeName, String supportColor, String rejectColor) {
            this.tag = tag;
            this.c1 = c1;
            this.c2 = c2;
            this.n1 = n1;
            this.n2 = n2;
            this.mode = mode;
            this.modeName = modeName;
            this.supportColor = supportColor;
            this.rejectColor = rejectColor;
        }
    }

    /**
     * 精确测试：设两个与目标模式"固定色完全相反"的颜色，再切到该模式。
     * - 灯显示我们设的颜色 → 固件渐变会采用用户颜色（重大突破）
     * - 灯显示该模式原本的固定色 → 固件不理我们设的颜色（不支持）
     */
    private void sendPreciseTest(final PreciseTest t) {
        if (BleController.get(activity).getConnectedCount() == 0) {
            if (tvStatus != null) tvStatus.setText("未连接设备，请先在主页连接灯具");
            return;
        }
        final byte[][] seq = new byte[][]{
                LedCommand.color((t.c1 >> 16) & 0xFF, (t.c1 >> 8) & 0xFF, t.c1 & 0xFF),
                LedCommand.color((t.c2 >> 16) & 0xFF, (t.c2 >> 8) & 0xFF, t.c2 & 0xFF),
                LedCommand.mode(t.mode)
        };
        for (int i = 0; i < seq.length; i++) {
            final byte[] f = seq[i];
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    BleController.get(activity).send(f);
                }
            }, i * 200L);
        }
        if (tvIndex != null) tvIndex.setText("判定测试 " + t.tag);
        if (tvLabel != null) {
            tvLabel.setText("设 " + t.n1 + " + " + t.n2 + " → 跑 " + t.modeName);
        }
        if (tvHex != null) {
            tvHex.setText(hex(seq[0]) + "  然后  " + hex(seq[1]) + "  然后  " + hex(seq[2]));
        }
        String msg = "看灯：显示【" + t.supportColor + "】= 用了我们设的颜色（支持！）｜"
                + "显示【" + t.rejectColor + "】= 固件固定色（不支持）";
        if (tvStatus != null) tvStatus.setText(msg);
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
    }

    private void moveTo(int i) {
        if (candidates == null || candidates.isEmpty()) return;
        index = ((i % candidates.size()) + candidates.size()) % candidates.size();
        updateUi();
    }

    private void updateUi() {
        if (candidates == null || candidates.isEmpty()) return;
        Candidate c = candidates.get(index);
        if (tvIndex != null) tvIndex.setText((index + 1) + " / " + candidates.size());
        if (tvLabel != null) tvLabel.setText(c.label);
        if (tvHex != null) tvHex.setText(hex(c.frames[0])
                + (c.frames.length > 1 ? "  （共 " + c.frames.length + " 条）" : ""));
    }

    private void updateFound() {
        if (tvFound == null) return;
        String r = Prefs.get(activity).getProbeResult();
        tvFound.setText(r == null || r.length() == 0
                ? "还没记录。有反应时点上面的按钮，这里会显示要找的命令。"
                : "已记录（拍照发我）：\n" + r);
    }

    /** 发送当前候选（多帧按 130ms 间隔依次发，避免 BLE 写拥塞） */
    private void sendCurrent() {
        if (candidates == null || candidates.isEmpty()) return;
        if (BleController.get(activity).getConnectedCount() == 0) {
            if (tvStatus != null) tvStatus.setText("未连接设备，请先在主页连接灯具");
            return;
        }
        final Candidate c = candidates.get(index);
        for (int i = 0; i < c.frames.length; i++) {
            final byte[] frame = c.frames[i];
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    BleController.get(activity).send(frame);
                }
            }, i * 130L);
        }
        if (tvStatus != null) {
            tvStatus.setText("已发送：" + hex(c.frames[0]));
        }
    }

    private void startLoop() {
        running = true;
        if (btnStart != null) btnStart.setText("暂停探测");
        if (tvStatus != null) tvStatus.setText("探测中…注视灯带变化");
        tick();
    }

    private void stopLoop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (btnStart != null) btnStart.setText("开始探测");
        if (tvStatus != null) tvStatus.setText("已暂停");
    }

    private void tick() {
        if (!running) return;
        sendCurrent();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running) sendCurrent();
            }
        }, RESEND_DELAY);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                moveTo(index + 1);
                tick();
            }
        }, NEXT_DELAY);
    }

    @Override
    public void onHide() {
        stopLoop();
    }
}
