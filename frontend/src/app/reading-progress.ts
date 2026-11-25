import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { Api, problem, ReadingSample, TitleKey } from './api';
import { ReadingBatch, ReadingOutbox } from './reading-outbox';

const matches = (a: TitleKey, b: TitleKey) => a.sourceId === b.sourceId && a.externalTitleId === b.externalTitleId;

@Injectable({providedIn: 'root'})
export class ReadingProgressStore {
  private readonly api = inject(Api);
  private readonly outbox = new ReadingOutbox();
  private readonly active = new Map<string, ReadingBatch>();
  private readonly locks = new Map<string, Promise<unknown>>();
  private readonly timer = new Map<string, number>();
  readonly feedback = signal<Record<string, {text: string; conflict: boolean; refresh: boolean}>>({});

  constructor() {
    window.addEventListener('online', () => void this.recover());
    window.addEventListener('pagehide', () => this.flush());
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.flush();
    });
    window.setInterval(() => void this.recover(), 5000);
    void this.recover();
  }

  id(key: TitleKey) { return key.sourceId + '/' + key.externalTitleId; }

  private lock<T>(id: string, work: () => Promise<T>): Promise<T> {
    const previous = this.locks.get(id) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(async () => navigator.locks
      ? await navigator.locks.request('kadr-reading-' + id, work) : await work());
    this.locks.set(id, next);
    void next.finally(() => { if (this.locks.get(id) === next) this.locks.delete(id); }).catch(() => undefined);
    return next;
  }

  async state(key: TitleKey) {
    await this.recover(key);
    const state = await firstValueFrom(this.api.reading(key).pipe(timeout(5000)));
    return state;
  }

  record(key: TitleKey, revision: string, sample: ReadingSample) {
    const id = this.id(key);
    let batch = this.active.get(id);
    if (!batch) {
      batch = {
        key, revision, sessionId: crypto.randomUUID(), claimId: crypto.randomUUID(),
        claimed: false, sequence: 0, pending: [],
      };
      this.active.set(id, batch);
    }
    const session = batch;
    if (session.conflict) return;
    // Persist the delta before scheduling network delivery.
    void this.lock(session.sessionId, async () => {
      const stored = await this.outbox.get(session.sessionId);
      if (stored) Object.assign(session, stored);
      if (session.conflict) return;
      const last = session.pending.at(-1);
      if (last && last.chapterId === sample.chapterId && last.language === sample.language &&
          last.downloadId === sample.downloadId && last.manifestVersion === sample.manifestVersion) {
        session.pending[session.pending.length - 1] = {
          ...sample,
          seenPageIndexes: [...new Set([...last.seenPageIndexes, ...sample.seenPageIndexes])],
          reachedEnd: last.reachedEnd || sample.reachedEnd,
          activeSecondsDelta: Math.min(15, last.activeSecondsDelta + sample.activeSecondsDelta),
        };
      } else session.pending.push(sample);
      await this.outbox.write(session);
      if (!this.timer.has(session.sessionId)) {
        this.timer.set(session.sessionId, window.setTimeout(() => {
          this.timer.delete(session.sessionId);
          void this.deliver(session);
        }, 800));
      }
    }).catch(() => this.status(key, 'Не удалось сохранить позицию на устройстве.'));
  }

  flush() {
    for (const session of this.active.values()) if (!session.conflict) void this.deliver(session);
  }

  async takeOver(key: TitleKey, reapply = true) {
    const stored = await this.outbox.all();
    const conflicts = stored.filter(batch => matches(batch.key, key) && batch.conflict);
    const old = conflicts.flatMap(batch => [batch.sending, ...batch.pending]).filter(item => item !== undefined).at(-1);
    const state = await firstValueFrom(this.api.reading(key).pipe(timeout(5000)));
    this.active.delete(this.id(key));
    for (const batch of conflicts) await this.outbox.write(batch, true);
    this.status(key, '');
    if (old && reapply) {
      const {chapterId, language, pageIndex, downloadId, manifestVersion} = old;
      this.record(key, state.revision, {chapterId, language, pageIndex, downloadId, manifestVersion,
        reachedEnd: false, seenPageIndexes: [pageIndex], activeSecondsDelta: 0});
    }
    return state;
  }

  private async recover(key?: TitleKey) {
    try {
      const stored = await this.outbox.all();
      for (const batch of stored) {
        if (key && !matches(key, batch.key)) continue;
        if (batch.conflict) {
          const active = this.active.get(this.id(batch.key));
          if (!active || active.sessionId === batch.sessionId) this.showConflict(batch);
        } else if (batch.sending || batch.pending.length) {
          if (key) await this.deliver(batch);
          else void this.deliver(batch);
        }
      }
    } catch { /* Reader reports storage failure when trying to persist a sample. */ }
  }

  private async deliver(session: ReadingBatch) {
    await this.lock('send-' + session.sessionId, async () => {
      const pending = await this.outbox.get(session.sessionId);
      if (!pending || pending.conflict || (!pending.sending && !pending.pending.length)) return;
      const key = pending.key;
      this.status(key, 'Сохраняем позицию…');
      try {
        if (!pending.claimed) {
          const claim = await firstValueFrom(this.api.claimReading(key, {
            eventId: pending.claimId, sessionId: pending.sessionId, expectedRevision: pending.revision,
          }).pipe(timeout(5000)));
          await this.change(pending, batch => {
            batch.revision = claim.revision;
            batch.claimed = true;
          });
        }
        for (;;) {
          const ready = await this.change(pending, batch => {
            if (batch.conflict || batch.sending || !batch.pending.length) return;
            const sample = batch.pending.shift()!;
            batch.sending = {...sample, eventId: crypto.randomUUID(), sequence: ++batch.sequence,
              sessionId: batch.sessionId, expectedRevision: batch.revision};
          });
          if (ready.conflict || !ready.sending) break;
          const sent = ready.sending;
          const saved = await firstValueFrom(this.api.saveReading(key, sent).pipe(timeout(5000)));
          const next = await this.change(pending, batch => {
            if (BigInt(saved.revision) !== BigInt(sent.expectedRevision) + 1n) {
              batch.conflict = 'READING_SESSION_STALE';
              return;
            }
            batch.sending = undefined;
            batch.revision = saved.revision;
          });
          if (next.conflict) {
            this.showConflict(next);
            return;
          }
        }
        this.status(key, 'Позиция сохранена');
      } catch (error) {
        const issue = problem(error);
        const status = (error as {status?: number}).status ?? 0;
        if (status >= 400 && status < 500 && status !== 429) {
          this.showConflict(await this.change(pending, batch => { batch.conflict = issue.code; }));
        } else this.status(key, 'Позиция сохранена на устройстве');
      }
    }).catch(() => this.status(session.key, 'Не удалось сохранить позицию на устройстве.'));
  }

  private change(session: ReadingBatch, update: (batch: ReadingBatch) => void) {
    return this.lock(session.sessionId, async () => {
      const batch = await this.outbox.get(session.sessionId) ?? session;
      update(batch);
      await this.outbox.write(batch);
      const active = this.active.get(this.id(batch.key));
      if (active?.sessionId === batch.sessionId) Object.assign(active, batch);
      return batch;
    });
  }

  private showConflict(batch: ReadingBatch) {
    const refresh = ['READING_MANIFEST_CHANGED', 'READING_CONTEXT_REQUIRED'].includes(batch.conflict ?? '');
    this.status(batch.key, refresh ? 'Список страниц изменился. Обновите главу.'
      : 'Серверная позиция изменилась. Выберите, какую сохранить.', true, refresh);
  }

  private status(key: TitleKey, text: string, conflict = false, refresh = false) {
    this.feedback.update(all => ({...all, [this.id(key)]: {text, conflict, refresh}}));
  }
}
