package com.zhj.panxia;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件列表适配器：支持多选、图标区分、右侧 ⋮ 菜单。
 *
 * 1.2 的两处关键修正：
 * - 行不再自带 OnClickListener，点击 / 长按统一交给 ListView 的
 *   onItemClick / onItemLongClick，框架会在惯性滚动期间抑制误触。
 * - 多选集合保存条目对象而不是文件名；数据集一换（刷新、换目录、筛选、
 *   换服务器）就自动剔除失效项，不会出现"莫名被勾选"。
 */
public class FileAdapter extends BaseAdapter {

    public interface OnMore {
        void onMore(int position);
    }

    private final Context ctx;
    private final List<Api.Entry> items = new ArrayList<>();
    private final Set<Api.Entry> selected = new LinkedHashSet<>();
    private boolean selectionMode;
    private OnMore more;

    public FileAdapter(Context c) {
        this.ctx = c;
    }

    public void setMoreListener(OnMore l) {
        this.more = l;
    }

    public void submit(List<Api.Entry> list) {
        items.clear();
        items.addAll(list);
        // 只保留仍属于本次数据集的条目，避免刷新 / 筛选 / 换目录后残留勾选
        selected.retainAll(new HashSet<Api.Entry>(items));
        notifyDataSetChanged();
    }

    public List<Api.Entry> items() {
        return items;
    }

    public Api.Entry at(int i) {
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int i) {
        return at(i);
    }

    @Override
    public long getItemId(int i) {
        Api.Entry e = at(i);
        return e == null ? -1L : e.name.hashCode();
    }

    public boolean selectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean on) {
        selectionMode = on;
        if (!on) {
            selected.clear();
        }
        notifyDataSetChanged();
    }

    /** 返回名称集合，供网络批量操作使用 */
    public Set<String> selected() {
        Set<String> out = new LinkedHashSet<>();
        for (Api.Entry e : selected) {
            out.add(e.name);
        }
        return out;
    }

    public List<String> selectedNames() {
        return new ArrayList<>(selected());
    }

    public int selectedCount() {
        return selected.size();
    }

    public void toggle(Api.Entry e) {
        if (e == null) {
            return;
        }
        if (!selected.remove(e)) {
            selected.add(e);
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        selected.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false);
            h = new Holder();
            h.check = v.findViewById(R.id.tvCheck);
            h.icon = v.findViewById(R.id.ivIcon);
            h.name = v.findViewById(R.id.tvName);
            h.meta = v.findViewById(R.id.tvMeta);
            h.more = v.findViewById(R.id.btnMore);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }

        final Api.Entry e = items.get(position);
        // 复用的行可能残留上一次的缩放动画，先取消再复位
        if (v.animate() != null) {
            v.animate().cancel();
        }
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);

        h.name.setText(e.name);
        if (e.dir) {
            h.meta.setText("文件夹" + (e.modified.isEmpty() ? "" : " · " + Ui.time(e.modified)));
            h.icon.setImageResource(R.drawable.ic_folder);
            h.icon.clearColorFilter();
        } else {
            String t = Ui.time(e.modified);
            h.meta.setText(Ui.size(e.size) + (t.isEmpty() ? "" : " · " + t));
            int icon = iconFor(e.name);
            h.icon.setImageResource(icon);
            if (icon == R.drawable.ic_file) {
                h.icon.setColorFilter(Ui.color(ctx, R.attr.olDim));
            } else {
                h.icon.clearColorFilter();
            }
        }

        boolean picked = selectionMode && selected.contains(e);
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setImageResource(picked ? R.drawable.ic_check_circle : R.drawable.ic_radio);
        h.check.setColorFilter(picked
                ? Ui.color(ctx, R.attr.olAccent)
                : Ui.color(ctx, R.attr.olDim));
        v.setBackgroundResource(picked ? R.drawable.bg_row_selected : R.drawable.bg_row);
        v.setActivated(picked);
        h.more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);

        final int pos = position;
        h.more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (more != null) {
                    more.onMore(pos);
                }
            }
        });
        return v;
    }

    private static int iconFor(String name) {
        switch (Mime.kind(name)) {
            case IMAGE:
                return R.drawable.ic_image;
            case VIDEO:
                return R.drawable.ic_video;
            case AUDIO:
                return R.drawable.ic_audio;
            case TEXT:
            case PDF:
            case OFFICE:
                return R.drawable.ic_doc;
            default:
                return R.drawable.ic_file;
        }
    }

    private static class Holder {
        ImageView check;
        ImageView icon;
        TextView name;
        TextView meta;
        ImageView more;
    }
}
