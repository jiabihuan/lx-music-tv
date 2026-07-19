package com.lx.tv.music;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌曲列表适配器
 * 显示歌曲名、歌手、专辑、音源标签
 * 支持遥控器焦点高亮（选中放大效果）
 * 支持点击播放
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.MusicViewHolder> {

    private final Context context;
    private final List<MusicInfo> musicList = new ArrayList<>();
    private final LayoutInflater inflater;
    private OnItemClickListener onItemClickListener;
    private int selectedPosition = -1;
    /** 当前正在播放的歌曲ID */
    private String playingMusicId = null;

    /** 音源颜色映射 */
    private static final int COLOR_KW = Color.parseColor("#FF6B35");
    private static final int COLOR_KG = Color.parseColor("#2196F3");
    private static final int COLOR_TX = Color.parseColor("#4CAF50");
    private static final int COLOR_WY = Color.parseColor("#E91E63");
    private static final int COLOR_MG = Color.parseColor("#9C27B0");

    /** 列表项点击监听器 */
    public interface OnItemClickListener {
        void onItemClick(MusicInfo music, int position);
    }

    public MusicAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    /**
     * 设置数据并刷新
     */
    public void setData(List<MusicInfo> data) {
        musicList.clear();
        if (data != null) {
            musicList.addAll(data);
        }
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    /**
     * 追加数据
     */
    public void appendData(List<MusicInfo> data) {
        if (data == null || data.isEmpty()) return;
        int start = musicList.size();
        musicList.addAll(data);
        notifyItemRangeInserted(start, data.size());
    }

    /**
     * 清空数据
     */
    public void clearData() {
        musicList.clear();
        selectedPosition = -1;
        notifyDataSetChanged();
    }

    public List<MusicInfo> getData() {
        return new ArrayList<>(musicList);
    }

    public MusicInfo getItem(int position) {
        if (position < 0 || position >= musicList.size()) return null;
        return musicList.get(position);
    }

    public int indexOf(MusicInfo music) {
        if (music == null) return -1;
        for (int i = 0; i < musicList.size(); i++) {
            if (music.equals(musicList.get(i))) return i;
        }
        return -1;
    }

    /**
     * 设置当前选中位置（用于焦点恢复）
     */
    public void setSelectedPosition(int position) {
        int old = selectedPosition;
        selectedPosition = position;
        if (old >= 0) notifyItemChanged(old);
        if (position >= 0) notifyItemChanged(position);
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    /**
     * 设置当前正在播放的歌曲
     */
    public void setPlayingMusic(MusicInfo music) {
        String oldId = playingMusicId;
        playingMusicId = (music != null && music.id != null) ? music.id : null;
        // 通知变化
        for (int i = 0; i < musicList.size(); i++) {
            MusicInfo m = musicList.get(i);
            String mid = m != null ? m.id : null;
            if (mid != null && (mid.equals(oldId) || mid.equals(playingMusicId))) {
                notifyItemChanged(i);
            }
        }
    }

    @NonNull
    @Override
    public MusicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = inflater.inflate(R.layout.item_music, parent, false);
        return new MusicViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MusicViewHolder holder, int position) {
        MusicInfo music = musicList.get(position);
        holder.bind(music, position);
    }

    @Override
    public int getItemCount() {
        return musicList.size();
    }

    class MusicViewHolder extends RecyclerView.ViewHolder {
        TextView tvIndex;
        TextView tvSongName;
        TextView tvSinger;
        TextView tvAlbum;
        TextView tvSource;
        TextView tvInterval;
        View playingIndicator;

        MusicViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIndex = itemView.findViewById(R.id.tv_index);
            tvSongName = itemView.findViewById(R.id.tv_song_name);
            tvSinger = itemView.findViewById(R.id.tv_singer);
            tvAlbum = itemView.findViewById(R.id.tv_album);
            tvSource = itemView.findViewById(R.id.tv_source);
            tvInterval = itemView.findViewById(R.id.tv_interval);
            playingIndicator = itemView.findViewById(R.id.playing_indicator);
        }

        void bind(final MusicInfo music, final int position) {
            // 序号
            tvIndex.setText(String.valueOf(position + 1));

            // 歌曲名
            String name = music.songName != null ? music.songName : "未知歌曲";
            tvSongName.setText(name);

            // 歌手
            String singer = music.singer != null ? music.singer : "未知歌手";
            tvSinger.setText(singer);

            // 专辑
            if (!TextUtils.isEmpty(music.album)) {
                tvAlbum.setText(music.album);
                tvAlbum.setVisibility(View.VISIBLE);
            } else {
                tvAlbum.setVisibility(View.GONE);
            }

            // 音源标签
            String sourceTag = music.getSourceDisplayName();
            tvSource.setText(sourceTag);
            tvSource.setBackgroundColor(getSourceColor(music.source));

            // 时长
            if (!TextUtils.isEmpty(music.interval)) {
                tvInterval.setText(music.interval);
                tvInterval.setVisibility(View.VISIBLE);
            } else {
                tvInterval.setVisibility(View.GONE);
            }

            // 当前播放指示
            boolean isPlaying = music.id != null && music.id.equals(playingMusicId);
            playingIndicator.setVisibility(isPlaying ? View.VISIBLE : View.INVISIBLE);
            if (isPlaying) {
                tvSongName.setTextColor(Color.parseColor("#FFC107"));
            } else {
                tvSongName.setTextColor(Color.parseColor("#FFFFFF"));
            }

            // 点击事件
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedPosition = position;
                    if (onItemClickListener != null) {
                        onItemClickListener.onItemClick(music, position);
                    }
                }
            });

            // 焦点变化监听（用于高亮）
            itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        selectedPosition = position;
                        // 焦点放大效果
                        v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                    }
                }
            });
        }
    }

    /**
     * 获取音源对应颜色
     */
    private int getSourceColor(String source) {
        if (source == null) return Color.GRAY;
        switch (source) {
            case "kw": return COLOR_KW;
            case "kg": return COLOR_KG;
            case "tx": return COLOR_TX;
            case "wy": return COLOR_WY;
            case "mg": return COLOR_MG;
            default: return Color.GRAY;
        }
    }
}
