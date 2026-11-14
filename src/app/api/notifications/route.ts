import { NextRequest, NextResponse } from 'next/server';

import { getAuthInfoFromCookie } from '@/lib/auth';
import { getStorage } from '@/lib/db';

export const runtime = 'nodejs';

// 已下线的功能可能仍留有历史通知，读取时统一过滤，避免跳到不存在的页面。
const REMOVED_NOTIFICATION_TYPES = new Set([
  'manga_update',
  'movie_request',
  'request_fulfilled',
  'request_rejected',
]);

// GET: 获取所有通知
export async function GET(request: NextRequest) {
  const authInfo = getAuthInfoFromCookie(request);
  if (!authInfo || !authInfo.username) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const storage = getStorage();
    const allNotifications = await storage.getNotifications(authInfo.username);
    const notifications = allNotifications.filter(
      (item) => !REMOVED_NOTIFICATION_TYPES.has(item.type)
    );
    const unreadCount = notifications.filter((item) => !item.read).length;

    return NextResponse.json({
      notifications,
      unreadCount,
    });
  } catch (error) {
    console.error('获取通知失败:', error);
    return NextResponse.json(
      { error: (error as Error).message },
      { status: 500 }
    );
  }
}

// POST: 标记通知为已读或删除通知
export async function POST(request: NextRequest) {
  const authInfo = getAuthInfoFromCookie(request);
  if (!authInfo || !authInfo.username) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const body = await request.json();
    const { action, notificationId } = body;

    const storage = getStorage();

    if (action === 'mark_read' && notificationId) {
      await storage.markNotificationAsRead(authInfo.username, notificationId);
      return NextResponse.json({ message: '已标记为已读' });
    }

    if (action === 'delete' && notificationId) {
      await storage.deleteNotification(authInfo.username, notificationId);
      return NextResponse.json({ message: '已删除' });
    }

    if (action === 'clear_all') {
      await storage.clearAllNotifications(authInfo.username);
      return NextResponse.json({ message: '已清空所有通知' });
    }

    return NextResponse.json({ error: '无效的操作' }, { status: 400 });
  } catch (error) {
    console.error('操作通知失败:', error);
    return NextResponse.json(
      { error: (error as Error).message },
      { status: 500 }
    );
  }
}
