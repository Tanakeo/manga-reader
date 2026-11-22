import {
  afterNextRender, Component, computed, DOCUMENT, effect, ElementRef, inject, Injector, input, linkedSignal, output,
  signal, viewChild, viewChildren,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideArrowLeft as ArrowLeft, LucideArrowRight as ArrowRight, LucideImageOff as ImageOff, LucideList as List, LucideDynamicIcon, LucideX as X } from '@lucide/angular';
import { Api, languageName, Problem, problem, readerLink, sameTitle, titleLink } from './api';
import { SkipLink, State } from './common';
import { chapterLabel, offlineLink } from './downloads';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { NavigationDock } from './navigation-dock';

@Component({
  selector: 'app-page-image',
  imports: [LucideDynamicIcon, RouterLink],
  host: {'[attr.id]': "'page-' + (index() + 1)"},
  template: `
    @if (failed()) {
      <div class="page-fallback" role="status">
        <svg class="icon" [lucideIcon]="imageOff" [size]="26" aria-hidden="true"/>
        <p>{{ imageIssue() || 'Изображение не загрузилось.' }} @if (offline()) { Проверьте загрузку главы. }</p>
        <div class="actions">
          <button class="button secondary" type="button" (click)="retry()">Повторить</button>
          @if (offline()) {
            <a class="button secondary" routerLink="/library/downloads">В скачанные главы</a>
          } @else {
            <a class="button secondary" [href]="source()" target="_blank" rel="noopener noreferrer">Читать в источнике</a>
          }
        </div>
      </div>
    } @else if (url(); as address) {
      <img [src]="address" [alt]="'Страница ' + (index() + 1)"
        [class.image-waiting]="!loaded()" [attr.loading]="eager() || retries() ? 'eager' : 'lazy'"
        decoding="async" (load)="ready()" (error)="ready(true)">
    } @else {
      <div class="page-loading" role="status" [attr.aria-label]="'Загружаем страницу ' + (index() + 1)"></div>
    }
  `,
})
export class PageImage {
  private readonly document = inject(DOCUMENT);
  readonly src = input.required<string>();
  readonly source = input.required<string>();
  readonly index = input.required<number>();
  readonly eager = input(false);
  readonly offline = input(false);
  readonly settled = output<number>();
  protected readonly loaded = signal(false);
  protected readonly failed = signal(false);
  protected readonly retries = signal(0);
  protected readonly imageOff = ImageOff;
  protected readonly url = signal<string | null>(null);
  protected readonly imageIssue = signal('');
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly imageDestroy = inject(DestroyRef);
  private readonly visible = signal(false);
  readonly manifestChanged = output<void>();

  constructor() {
    afterNextRender(() => {
      if (typeof IntersectionObserver === 'undefined') { this.visible.set(true); return; }
      const observer = new IntersectionObserver(entries => {
        if (entries.some(entry => entry.isIntersecting)) {
          this.visible.set(true);
          observer.disconnect();
        }
      }, {rootMargin: '600px'});
      observer.observe(this.host.nativeElement);
      this.imageDestroy.onDestroy(() => observer.disconnect());
    });
    effect(cleanup => {
      const source = this.src();
      this.retries();
      if (!this.visible() && !this.eager()) return;
      this.loaded.set(false);
      this.failed.set(false);
      this.url.set(null);
      let objectUrl: string | undefined;
      const request = this.api.image(source).subscribe({
        next: blob => { objectUrl = URL.createObjectURL(blob); this.url.set(objectUrl); },
        error: error => {
          const issue = problem(error);
          this.imageIssue.set(issue.detail);
          this.ready(true);
          if (issue.code === 'PAGE_MANIFEST_CHANGED') this.manifestChanged.emit();
        },
      });
      cleanup(() => { request.unsubscribe(); if (objectUrl) URL.revokeObjectURL(objectUrl); });
    });
  }

  protected ready(failed = false) {
    this.failed.set(failed);
    this.loaded.set(!failed);
    this.settled.emit(this.index());
  }

  protected retry() {
    this.retries.update(value => value + 1);
    this.failed.set(false);
  }
}

@Component({
  imports: [RouterLink, LucideDynamicIcon, State, PageImage, SkipLink, LocalizedTitle, NavigationDock],
  templateUrl: './reader.html',
  host: {'class': 'reader', '(window:scroll)': 'syncPosition()'},
})
export class Reader {
  protected readonly titleLanguage = inject(TitleLocale).language;
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  private readonly injector = inject(Injector);
  readonly sourceId = input('');
  readonly externalTitleId = input('');
  readonly chapterId = input('');
  readonly language = input('');
  readonly page = input<string>();
  readonly downloadId = input<string>();
  protected readonly offline = computed(() => this.downloadId() !== undefined);
  protected readonly key = computed(() => ({sourceId: this.sourceId(), externalTitleId: this.externalTitleId()}));
  private readonly request = computed(() => ({
    key: this.key(), chapterId: this.chapterId(), language: this.language(), downloadId: this.downloadId(),
  }));
  private readonly manifest = rxResource({
    params: () => this.offline() ? this.downloadId() : undefined,
    stream: ({params}) => this.api.offline(params),
  });
  private readonly local = computed(() => {
    if (!this.manifest.hasValue()) return undefined;
    const manifest = this.manifest.value();
    if (!this.sourceId()) return manifest;
    return sameTitle(manifest.title, this.key()) && manifest.chapter.chapterId === this.chapterId()
      && manifest.chapter.language === this.language() ? manifest : undefined;
  });
  private readonly title = rxResource({
    params: () => this.offline() ? undefined : this.key(), stream: ({params}) => this.api.title(params),
  });
  protected readonly content = rxResource({
    params: () => this.offline() ? undefined : this.request(),
    stream: ({params}) => this.api.pages(params.key, params.chapterId, params.language),
  });
  protected readonly showChapters = signal(false);
  protected readonly chapters = rxResource({
    params: () => this.showChapters() && !this.offline() ? this.key() : undefined,
    stream: ({params}) => this.api.chapters(params),
  });
  protected readonly readerTitle = computed(() => this.offline()
    ? this.local()?.title : this.title.hasValue() ? this.title.value() : undefined);
  protected readonly chapterContent = computed(() => this.offline()
    ? this.local()?.chapter : this.content.hasValue() ? this.content.value() : undefined);
  protected readonly waiting = computed(() => this.offline() ? this.manifest.isLoading() : this.content.isLoading());
  protected readonly issue = computed<Problem | undefined>(() => {
    const error = this.offline() ? this.manifest.error() : this.content.error();
    if (error) return problem(error);
    if (this.offline() && this.manifest.hasValue() && !this.local()) {
      return {code: 'DOWNLOAD_MISMATCH', detail: 'Скачанная глава не совпадает с главой в ссылке.', errors: []};
    }
    return undefined;
  });
  protected readonly offlineChapters = computed(() => this.local()?.available ?? []);
  protected readonly localNeighbours = computed(() => {
    const chapters = this.offlineChapters().filter(item => item.language === this.local()?.chapter.language);
    const here = chapters.findIndex(item => item.id === this.downloadId());
    return here < 0 ? {} : {previous: chapters[here - 1], next: chapters[here + 1]};
  });
  protected readonly current = linkedSignal(() => { this.request(); return 0; });
  private readonly readyImages = linkedSignal(() => { this.request(); return new Set<number>(); });
  protected readonly targetPage = computed(() => {
    const requested = Number(this.page());
    const chapter = this.chapterContent();
    return chapter?.pageCount && Number.isInteger(requested) && requested > 1
      ? Math.min(requested - 1, chapter.pageCount - 1) : 0;
  });
  private readonly restored = linkedSignal(() => { this.request(); this.page(); return false; });
  private readonly images = viewChildren(PageImage, {read: ElementRef<HTMLElement>});
  private readonly bar = viewChild<ElementRef<HTMLElement>>('bar');
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('chaptersDialog');
  protected readonly back = ArrowLeft;
  protected readonly next = ArrowRight;
  protected readonly list = List;
  protected readonly close = X;
  protected readonly link = readerLink;
  protected readonly titleLink = titleLink;
  protected readonly offlineLink = offlineLink;
  protected readonly chapterLabel = chapterLabel;
  protected readonly lang = languageName;
  protected readonly problem = problem;
  private readonly library = rxResource({
    params: () => this.local()?.title, stream: () => this.api.library(),
  });
  protected readonly entry = computed(() => {
    const title = this.local()?.title;
    return title && this.library.hasValue() ? this.library.value().find(item => sameTitle(item.title, title)) : undefined;
  });
  protected readonly saving = signal(false);
  protected readonly saveFeedback = linkedSignal(() => { this.downloadId(); return ''; });

  protected savePosition() {
    const entry = this.entry();
    const chapter = this.local()?.chapter;
    if (!entry || !chapter || this.saving()) return;
    const route = this.downloadId();
    this.saving.set(true);
    this.api.update(entry.id, {progress: {chapterId: chapter.chapterId, language: chapter.language, pageIndex: this.current()}})
      .pipe(takeUntilDestroyed(this.destroy)).subscribe({
        next: () => {
          if (route === this.downloadId()) this.saveFeedback.set('Позиция сохранена.');
          this.saving.set(false);
        },
        error: error => {
          if (route === this.downloadId()) this.saveFeedback.set(problem(error).detail);
          this.saving.set(false);
        },
      });
  }

  constructor() {
    effect(() => {
      const target = this.targetPage();
      const pages = this.images();
      if (!target || this.restored() || !pages[target]) return;
      const ready = this.readyImages();
      if (Array.from({length: target + 1}, (_, index) => index).some(index => !ready.has(index))) return;
      afterNextRender(() => {
        const pageTop = pages[target].nativeElement.getBoundingClientRect().top + window.scrollY;
        const headerHeight = this.bar()?.nativeElement.offsetHeight ?? 0;
        window.scrollTo({top: pageTop - headerHeight - 8});
        this.current.set(target);
        this.restored.set(true);
      }, {injector: this.injector});
    });
  }

  protected markReady(index: number) {
    this.readyImages.update(previous => new Set(previous).add(index));
  }

  protected syncPosition() {
    const pages = this.images();
    if (!pages.length) return;
    const viewport = window.innerHeight;
    const bottom = this.bar()?.nativeElement.ownerDocument.documentElement.scrollHeight;
    if (bottom && window.scrollY + viewport >= bottom - 2) {
      this.current.set(pages.length - 1);
      return;
    }
    const threshold = (this.bar()?.nativeElement.offsetHeight ?? 0) + 24;
    const current = pages.findIndex(page => page.nativeElement.getBoundingClientRect().bottom > threshold);
    if (current >= 0) this.current.set(current);
  }

  protected openChapters() {
    this.showChapters.set(true);
    this.dialog().nativeElement.showModal();
  }

  protected closeChapters() {
    this.dialog().nativeElement.close();
  }

  protected retryContent() {
    if (this.offline()) this.manifest.reload();
    else this.content.reload();
  }
}
