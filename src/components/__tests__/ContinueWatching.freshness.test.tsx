import { render, screen, waitFor } from '@testing-library/react';
import React from 'react';

import { getAllPlayRecords, getCachedPlayRecordsSnapshot } from '@/lib/db.client';
import { getFreshEpisodeDetail } from '@/lib/episode-detail.client';

import ContinueWatching from '@/components/ContinueWatching';

jest.mock('@/lib/db.client', () => ({
  getAllPlayRecords: jest.fn(),
  getCachedPlayRecordsSnapshot: jest.fn(),
  subscribeToDataUpdates: () => () => undefined,
  clearAllPlayRecords: jest.fn(),
}));
jest.mock('@/lib/episode-detail.client', () => ({ getFreshEpisodeDetail: jest.fn() }));
jest.mock('@/lib/special-source.client', () => ({ isSpecialSourceContext: () => false }));
jest.mock('@/components/PlayRecordsPanel', () => () => null);
jest.mock('@/components/VirtualScrollableRow', () => ({ children }: { children: React.ReactNode }) => <div>{children}</div>);
jest.mock('@/components/VideoCard', () => (props: { id: string; episodes: number; currentEpisode: number; progress: number }) => (
  <div data-testid={`record-${props.id}`}>{`${props.episodes} episodes, index ${props.currentEpisode}, progress ${props.progress}`}</div>
));

function record(id = '1') {
  return { title: `series-${id}`, cover: '', source_name: 'fixture', year: '2026',
    index: 2, total_episodes: 3, play_time: 30, total_time: 120, save_time: Number(id) };
}

beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  (getCachedPlayRecordsSnapshot as jest.Mock).mockReturnValue({});
});

it('updates a 3-episode snapshot to 5 without writing or altering episode/progress', async () => {
  const saved = record();
  (getAllPlayRecords as jest.Mock).mockResolvedValue({ 'fixture+1': saved });
  (getFreshEpisodeDetail as jest.Mock).mockResolvedValue({ episodes: ['1', '2', '3', '4', '5'] });
  render(<ContinueWatching />);
  await waitFor(() => expect(screen.getByTestId('record-1')).toHaveTextContent('5 episodes, index 2, progress 25'));
  expect(saved).toEqual(record());
  expect(getFreshEpisodeDetail).toHaveBeenCalledTimes(1);
});

it('keeps the snapshot and progress when a detail request fails', async () => {
  (getAllPlayRecords as jest.Mock).mockResolvedValue({ 'fixture+1': record() });
  (getFreshEpisodeDetail as jest.Mock).mockRejectedValue(new Error('offline'));
  render(<ContinueWatching />);
  await waitFor(() => expect(getFreshEpisodeDetail).toHaveBeenCalledTimes(1));
  expect(screen.getByTestId('record-1')).toHaveTextContent('3 episodes, index 2, progress 25');
});

it('limits homepage scans to six and never runs more than two at once', async () => {
  (getAllPlayRecords as jest.Mock).mockResolvedValue(Object.fromEntries(
    Array.from({ length: 20 }, (_, i) => [`fixture+${i + 1}`, record(String(i + 1))])
  ));
  let active = 0;
  let peak = 0;
  (getFreshEpisodeDetail as jest.Mock).mockImplementation(async () => {
    active++;
    peak = Math.max(peak, active);
    await new Promise((resolve) => setTimeout(resolve, 1));
    active--;
    return { episodes: ['1', '2', '3', '4', '5'] };
  });
  render(<ContinueWatching />);
  await waitFor(() => expect(getFreshEpisodeDetail).toHaveBeenCalledTimes(6));
  expect(peak).toBe(2);
});

it('does not start queued source requests after leaving the homepage', async () => {
  (getAllPlayRecords as jest.Mock).mockResolvedValue(Object.fromEntries(
    Array.from({ length: 10 }, (_, i) => [`fixture+${i + 1}`, record(String(i + 1))])
  ));
  const finish: Array<(value: unknown) => void> = [];
  (getFreshEpisodeDetail as jest.Mock).mockImplementation(() => new Promise(resolve => finish.push(resolve)));
  const view = render(<ContinueWatching />);
  await waitFor(() => expect(getFreshEpisodeDetail).toHaveBeenCalledTimes(2));
  view.unmount();
  finish.forEach(resolve => resolve({ episodes: ['1', '2', '3', '4'] }));
  await Promise.resolve();
  expect(getFreshEpisodeDetail).toHaveBeenCalledTimes(2);
});
