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
 * 本地文件列表适配器。
 *
 * 与远程页不同的两个关键点（也是 1.2 修掉的触控问题）：
 * 1. 行本身不设 clickable、不挂 OnClickListener，点击与长按统一交给 ListView 的
 *    onItemClick / onItemLongClick，由框架在惯性滚动期间抑制误触。
 * 2. 多选集合以完整路径为键，并在数据集变化时剔除已不存在的项，
 *    避免同名文件、刷新、切换目录后出现"莫名勾选"。
 */
public class LocalAdapter extends BaseAdapter {

    public interface OnMore {
        void onMore(int position);
    }

    private final Context ctx;
    private final List<LocalFs.Entry> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selectionMode;
    private OnMore more;

    public LocalAdapter(Context c) {
        this.ctx = c;
    }

    public void setMoreListener(OnMore l) {
        this.more = l;
    }

    /** 换数据集时只保留仍然可见的选中项 */
    public void submit(List<LocalFs.Entry> list) {
        items.clear();
        items.addAll(list);
        Set<String> valid = new HashSet<>();
        for (LocalFs.Entry e : items) {
            valid.add(e.key());
        }
        selected.retainAll(valid);
        notifyDataSetChanged();
    }

    public List<LocalFs.Entry> items() {
        return items;
    }

    public LocalFs.Entry at(int i) {
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
        LocalFs.Entry e = at(i);
        return e == null ? -1L : e.key().hashCode();
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    public boolean selectionMode() {
        return selectionMode;
    }

    public int selectedCount() {
        return selected.size();
    }

    public List<String> selectedPaths() {
        return new ArrayList<>(selected);
    }

    /** 进入多选并选中第一项，只触发一次刷新 */
    public void enterSelection(String firstKey) {
        selectionMode = true;
        if (firstKey != null) {
            selected.add(firstKey);
        }
        notifyDataSetChanged();
    }

    public void exitSelection() {
        selectionMode = false;
        selected.clear();
        notifyDataSetChanged();
    }

    public void toggle(String key) {
        if (!selected.remove(key)) {
            selected.add(key);
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (LocalFs.Entry e : items) {
            selected.add(e.key());
        }
        notifyDataSetChanged();
    }

    public boolean isSelected(String key) {
        return selected.contains(key);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_local, parent, false);
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

        final LocalFs.Entry e = items.get(position);
        // 复用的行可能仍带着上一次的缩放动画，先取消再复位
        if (v.animate() != null) {
            v.animate().cancel();
        }
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);

        h.name.setText(e.name);
        String when = Ui.time(e.modified);
        if (e.dir) {
            h.meta.setText(ctx.getString(R.string.folder_meta,
                    when.isEmpty() ? ctx.getString(R.string.unknown_time) : when));
        } else {
            h.meta.setText(ctx.getString(R.string.file_meta,
                    LocalFs.size(e.size), LocalFs.typeName(e),
                    when.isEmpty() ? ctx.getString(R.string.unknown_time) : when));
        }

        int icon = iconFor(e);
        h.icon.setImageResource(icon);
        if (icon == R.drawable.ic_file) {
            h.icon.setColorFilter(Ui.color(ctx, R.attr.olDim));
        } else {
            h.icon.clearColorFilter();
        }

        boolean picked = selectionMode && selected.contains(e.key());
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setImageResource(picked ? R.drawable.ic_check_circle : R.drawable.ic_radio);
        h.check.setColorFilter(Ui.color(ctx, picked ? R.attr.olAccent : R.attr.olDim));
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

    private static int iconFor(LocalFs.Entry e) {
        if (e.dir) {
            return R.drawable.ic_folder;
        }
        switch (Mime.kind(e.name)) {
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
