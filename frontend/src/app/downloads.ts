import { Component, computed, DestroyRef, effect, ElementRef, inject, input, Signal, signal, viewChild } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { finalize, Observable } from 'rxjs';
import { Api, Download, languageName, problem, Progress, sameTitle, TitleKey } from './api';
import { State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';

export function downloadState(item: Download) {
  switch (item.status) {
    case 'QUEUED': return 'В очереди';
    case 'PAUSED': return 'На паузе';
    case 'DOWNLOADING': return item.totalPages
      ? `Загрузка · ${item.pagesDone} из ${item.totalPages}` : 'Загружаем…';
    case 'READY': return 'Скачано';
    case 'FAILED': return item.error?.detail || 'Не удалось скачать главу.';
    case 'DELETING': return item.error?.detail || 'Удаляем…';
  }
}

export function offlineLink(item: Download) {
  return ['/downloads', item.id, 'read'];
}

export function chapterLabel(item: Download) {
  if (item.chapterNumber !== null) return String(item.chapterNumber);
  const episode = /^ep(\d+)_/.exec(item.chapterId);
  return episode ? String(Number(episode[1])) : item.chapterId;
}

export class DownloadStore {
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  readonly data;
  readonly items;
  readonly busy = signal(new Set<string>());
  readonly issue = signal('');

  constructor(private readonly scope?: Signal<TitleKey>) {
    this.data = rxResource({
      params: () => this.scope?.() ?? null,
      stream: ({params}) => this.api.downloads(params ?? undefined),
    });
    this.items = computed(() => this.data.hasValue() ? this.data.value().items : []);
    effect(onCleanup => {
      if (this.data.isLoading() || !this.items().some(item =>
        item.status === 'QUEUED' || item.status === 'DOWNLOADING' || item.status === 'DELETING')) return;
      const timer = setTimeout(() => this.data.reload(), 1400);
      onCleanup(() => clearTimeout(timer));
    });
  }

  start(chapterId: string, language: string) {
    const key = this.scope?.();
    if (key) this.run(this.key(chapterId, language), this.api.download(key, chapterId, language));
  }

  find(chapterId: string, language: string) {
    return this.items().find(item => item.chapterId === chapterId && item.language === language);
  }

  starting(chapterId: string, language: string) {
    return this.busy().has(this.key(chapterId, language));
  }

  retry(item: Download) {
    this.run(item.id, this.api.retryDownload(item.id));
  }

  control(item: Download, action: 'pause' | 'resume' | 'prioritize') {
    this.run(item.id,this.api.controlDownload(item.id,action),() => this.data.reload());
  }

  remove(item: Download, done: () => void) {
    this.run(item.id, this.api.deleteDownload(item.id), done);
  }

  continueParams(title: TitleKey, progress: Progress) {
    const ready = this.items().find(item => item.status === 'READY' && sameTitle(item.title, title)
      && item.chapterId === progress.chapterId && item.language === progress.language);
    return {page: progress.pageIndex + 1, downloadId: ready?.id ?? null};
  }

  private key(chapterId: string, language: string) {
    return chapterId + '/' + language;
  }

  private run(key: string, request: Observable<Download | null>, done?: () => void) {
    if (this.busy().has(key)) return;
    this.issue.set('');
    this.setBusy(key, true);
    request.pipe(takeUntilDestroyed(this.destroy), finalize(() => this.setBusy(key, false))).subscribe({
      next: item => {
        if (this.data.hasValue()) {
          const scope = this.scope?.();
          if (!item || !scope || sameTitle(scope, item.title)) {
            const current = this.data.value();
            const remaining = current.items.filter(existing => existing.id !== (item?.id ?? key));
            this.data.value.set({...current, items: item ? [item, ...remaining] : remaining});
          }
        } else {
          this.data.reload();
        }
        done?.();
      },
      error: error => this.issue.set(problem(error).detail),
    });
  }

  private setBusy(key: string, busy: boolean) {
    this.busy.update(previous => {
      const next = new Set(previous);
      if (busy) next.add(key);
      else next.delete(key);
      return next;
    });
  }
}

@Component({
  selector: 'app-downloads',
  imports: [RouterLink, State, LocalizedTitle],
  templateUrl: './downloads.html',
})
export class DownloadsPage {
  readonly embedded = input(false);
  readonly query = input('');
  protected readonly store = new DownloadStore();
  protected readonly visibleItems = computed(() => {
    const query = this.query().toLocaleLowerCase();
    return this.store.items().filter(item => !query ||
      [item.title.title, item.title.titleRu, item.title.titleEn].some(name => name?.toLocaleLowerCase().includes(query)));
  });
  protected readonly locale = inject(TitleLocale).language;
  protected readonly state = downloadState;
  protected readonly chapterLabel = chapterLabel;
  protected readonly lang = languageName;
  protected readonly link = offlineLink;
  protected readonly problem = problem;
  protected readonly confirming = signal<Download | undefined>(undefined);
  protected readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('deleteDialog');

  protected openDelete(item: Download) {
    this.store.issue.set('');
    this.confirming.set(item);
    this.dialog().nativeElement.showModal();
  }

  protected cancel(event: Event) {
    if (this.confirming() && this.store.busy().has(this.confirming()!.id)) event.preventDefault();
  }

  protected close() {
    this.dialog().nativeElement.close();
  }

  protected remove(item: Download) {
    this.store.remove(item, () => {
      this.close();
      this.heading().nativeElement.focus();
    });
  }
}
