-- 音乐与电子书功能已整体移除（301.0.0）。
-- 全部使用 IF EXISTS：Cloudflare 部署会按文件名顺序执行整个目录，
-- 重复执行必须安全。先删子表，避免外键依赖导致失败。

DROP TABLE IF EXISTS music_v2_playlist_items;
DROP TABLE IF EXISTS music_v2_playlists;
DROP TABLE IF EXISTS music_v2_history;

-- 旧版音乐表：新装库不会创建，老库可能仍存在
DROP TABLE IF EXISTS music_playlist_songs;
DROP TABLE IF EXISTS music_playlists;
DROP TABLE IF EXISTS music_play_records;

DROP TABLE IF EXISTS book_read_records;
DROP TABLE IF EXISTS book_shelf;
