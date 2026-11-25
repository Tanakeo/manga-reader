import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize, switchMap } from 'rxjs';
import { Api, languageName, problem, readerLink, ReadingHistoryItem, titleLink } from './api';
import { State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';

@Component({
  imports: [RouterLink, DatePipe, State, LocalizedTitle],
  templateUrl: './reading-history.html',
  styles: [`
    .history-row {
      grid-template-columns: minmax(0, 1fr) auto;
      overflow-wrap: anywhere;
    }
    .history-row h2 { font-size: 1rem; }
    .history-row a:not(.button):focus-visible { color: var(--accent); }
    @media (max-width: 600px) {
      .history-row { grid-template-columns: minmax(0, 1fr); }
    }
  `],
})
export class ReadingHistoryPage {
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  protected readonly locale = inject(TitleLocale).language;
  protected readonly items = signal<ReadingHistoryItem[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly marking = signal(false);
  protected readonly lang = languageName;
  protected readonly titleLink = titleLink;
  protected readonly readerLink = readerLink;

  constructor() { this.load(); }

  protected key(item: ReadingHistoryItem) {
    return [item.title.sourceId, item.title.externalTitleId, item.chapterId, item.language].join('/');
  }

  protected load(cursor?: string) {
    if (this.loading()) return;
    this.error.set('');
    this.loading.set(true);
    this.api.readingHistory(cursor).pipe(takeUntilDestroyed(this.destroy),
      finalize(() => this.loading.set(false))).subscribe({
      next: result => {
        const combined = new Map((cursor ? this.items() : []).map(item => [this.key(item), item]));
        for (const item of result.items) combined.set(this.key(item), item);
        this.items.set([...combined.values()]);
        this.cursor.set(result.page.nextCursor);
      },
      error: error => this.error.set(problem(error).detail),
    });
  }

  protected mark(item: ReadingHistoryItem) {
    if (this.marking()) return;
    this.marking.set(true);
    this.error.set('');
    this.api.reading(item.title).pipe(switchMap(state =>
      this.api.markReading(item.title, item.chapterId, item.language, !item.isRead, state.revision)),
      takeUntilDestroyed(this.destroy), finalize(() => this.marking.set(false))).subscribe({
      next: result => {
        if (result.chapter) this.items.update(items => items.map(row =>
          this.key(row) === this.key(item) ? {...row, isRead: result.chapter!.isRead} : row));
      },
      error: error => this.error.set(problem(error).detail),
    });
  }
}
