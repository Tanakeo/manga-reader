import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Api, LibraryUpdateItem, languageName, problem, readerLink, titleLink } from './api';
import { State } from './common';
import { TitleLocale } from './title-locale';

@Component({
  imports: [RouterLink, DatePipe, State],
  templateUrl: './library-updates.html',
  styles: [`.update-row {grid-template-columns: minmax(0,1fr) auto;overflow-wrap:anywhere}
    @media(max-width:600px){.update-row {grid-template-columns:1fr}}`],
})
export class LibraryUpdates {
  private readonly api = inject(Api);
  protected readonly locale = inject(TitleLocale).language;
  protected readonly items = signal<LibraryUpdateItem[]>([]);
  protected readonly cursor = signal<number | null>(null);
  protected readonly count = signal(0);
  protected readonly pending = signal(false);
  protected readonly issue = signal('');
  protected readonly language = signal('');
  protected readonly lang = languageName;
  protected readonly reader = readerLink;
  protected readonly title = titleLink;

  constructor() { this.load(); }

  protected filter(event: Event) {
    this.language.set((event.target as HTMLSelectElement).value);
    this.load();
  }

  protected load(cursor?: number) {
    if (this.pending()) return;
    this.pending.set(true);
    this.issue.set('');
    this.api.libraryUpdates(this.language(),cursor).pipe(finalize(() => this.pending.set(false))).subscribe({
      next: feed => {
        this.items.set(cursor ? [...this.items(),...feed.items] : feed.items);
        this.cursor.set(feed.nextCursor);
        this.count.set(feed.unread);
      },
      error: error => this.issue.set(problem(error).detail),
    });
  }
}
