package com.zhj.panxia;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 文件列表适配器：支持多选、图标区分、右侧 ⋮ 菜单。 */
public class FileAdapter extends BaseAdapter {

    public interface OnMore {
        void onMore(int position);
    }

    /**
     * 行点击。直接挂在行 View 上，不依赖 ListView 的 onItemClick，
     * 这样行内即使挂了触摸动画也不会把点击"吃掉"。
     */
    public interface OnRow {
        void onOpen(int position);

        void onLong(int position);
    }

    private final Context ctx;
    private final List<Api.Entry> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selectionMode;
    private OnMore more;
    private OnRow row;

    public FileAdapter(Context c) {
        this.ctx = c;
    }

    public void setMoreListener(OnMore l) {
        this.more = l;
    }

    public void setRowListener(OnRow l) {
        this.row = l;
    }

    public void submit(List<Api.Entry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public List<Api.Entry> items() {
        return items;
    }

    public Api.Entry at(int i) {
        return items.get(i);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int i) {
        return items.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
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

    public Set<String> selected() {
        return selected;
    }

    public List<String> selectedNames() {
        return new ArrayList<>(selected);
    }

    public void toggle(String name) {
        if (!selected.remove(name)) {
            selected.add(name);
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (Api.Entry e : items) {
            selected.add(e.name);
        }
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
        // 复用的行可能残留上一次按下动画的缩放，先复位，避免"越点越小"或点不到
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        h.name.setText(e.name);
        if (e.dir) {
            h.meta.setText("文件夹" + (e.modified.isEmpty() ? "" : " · " + Ui.time(e.modified)));
            h.icon.setImageResource(R.drawable.ic_folder);
        } else {
            String t = Ui.time(e.modified);
            h.meta.setText(Ui.size(e.size) + (t.isEmpty() ? "" : " · " + t));
            h.icon.setImageResource(iconFor(e.name));
        }

        boolean picked = selectionMode && selected.contains(e.name);
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setImageResource(picked ? R.drawable.ic_check_circle : R.drawable.ic_radio);
        h.check.setColorFilter(picked
                ? resolveColor(R.attr.olAccent)
                : resolveColor(R.attr.olDim));
        if (!Boolean.valueOf(picked).equals(v.getTag(R.id.tag_selected))) {
            v.setTag(R.id.tag_selected, picked);
            v.setBackgroundResource(picked ? R.drawable.bg_row_selected : R.drawable.bg_row);
        }
        h.more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);

        // 行点击直接挂在行 View 上：不占用 ListView 的 itemClick 判定，
        // 行内也不再挂 OnTouchListener，避免触摸被"吃掉"导致点不进目录。
        final int pos = position;
        v.setClickable(true);
        v.setFocusable(false);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (row != null) {
                    row.onOpen(pos);
                }
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (row != null) {
                    row.onLong(pos);
                }
                return true;
            }
        });

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

    private int resolveColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
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
