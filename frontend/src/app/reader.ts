import {
  afterNextRender, Component, computed, effect, ElementRef, inject, Injector, input, linkedSignal, output,
  signal, viewChild, viewChildren,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DestroyRef } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { LucideArrowLeft as ArrowLeft, LucideArrowRight as ArrowRight, LucideImageOff as ImageOff, LucideList as List, LucideDynamicIcon, LucideX as X } from '@lucide/angular';
import { Api, languageName, Problem, problem, readerLink, sameTitle, titleLink } from './api';
import { ReadingProgressStore } from './reading-progress';
import { from } from 'rxjs';
import { SkipLink, State } from './common';
import { chapterLabel, offlineLink } from './downloads';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { NavigationDock } from './navigation-dock';
import { TitlePreviewState } from './shared/title-preview-state';

interface ReaderSettings {
  mode: 'paged' | 'vertical';
  width: number;
  rtl: boolean;
  keys: boolean;
  prefetch: number;
}
const DEFAULT_SETTINGS: ReaderSettings = {mode:'vertical',width:760,rtl:false,keys:true,prefetch:1};

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
      <div class="page-loading" role="status" [style.aspect-ratio]="imageRatio()"
        [style.min-height.px]="imageRatio() ? 160 : null" [attr.aria-label]="'Загружаем страницу ' + (index() + 1)"></div>
    }
  `,
})
export class PageImage {
  readonly src = input.required<string>();
  readonly source = input.required<string>();
  readonly index = input.required<number>();
  readonly eager = input(false);
  readonly permitted = input(true);
  readonly offline = input(false);
  readonly settled = output<{index: number; loaded: boolean}>();
  protected readonly loaded = signal(false);
  protected readonly failed = signal(false);
  protected readonly retries = signal(0);
  protected readonly imageOff = ImageOff;
  protected readonly url = signal<string | null>(null);
  protected readonly imageIssue = signal('');
  protected readonly imageRatio = signal<string | null>(null);
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly imageDestroy = inject(DestroyRef);
  private readonly visible = signal(false);
  readonly manifestChanged = output<void>();
  readonly reset = output<number>();

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
      if (!this.permitted() || (!this.visible() && !this.eager())) {
        this.url.set(null);
        this.reset.emit(this.index());
        return;
      }
      this.loaded.set(false);
      this.failed.set(false);
      this.url.set(null);
      this.reset.emit(this.index());
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
    const image = this.host.nativeElement.querySelector('img');
    if (!failed && image?.naturalWidth && image.naturalHeight) {
      this.imageRatio.set(image.naturalWidth + ' / ' + image.naturalHeight);
    }
    this.failed.set(failed);
    this.loaded.set(!failed);
    this.settled.emit({index: this.index(), loaded: !failed});
  }

  protected retry() {
    this.retries.update(value => value + 1);
    this.failed.set(false);
  }
}

@Component({
  imports: [RouterLink, LucideDynamicIcon, State, PageImage, SkipLink, LocalizedTitle, NavigationDock],
  templateUrl: './reader.html',
  host: {'class': 'reader', '(window:scroll)': 'syncPosition()', '(window:wheel)': 'readingInteraction()',
    '(window:touchmove)': 'readingInteraction()', '(window:keydown)': 'readingKeydown($event)',
    '(window:pointerdown)': 'readingInteraction()'},
})
export class Reader {
  protected readonly preview=inject(TitlePreviewState);
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
  private readonly readyImages = linkedSignal(() => { this.request(); return new Map<number, boolean>(); });
  private readonly progress = inject(ReadingProgressStore);
  private readonly router = inject(Router);
  private readonly readingKey = computed(() => this.offline() ? this.local()?.title
    : this.chapterContent() ? this.key() : undefined);
  protected readonly reading = rxResource({
    params: this.readingKey, stream: ({params}) => from(this.progress.state(params)),
  });
  protected readonly saveFeedback = computed(() => {
    const key = this.readingKey();
    if (!key) return undefined;
    return this.progress.feedback()[this.progress.id(key)];
  });
  protected readonly settingsOpen = signal(false);
  protected readonly panelsHidden = signal(false);
  protected readonly bookmarkStatus = signal('');
  protected readonly bookmarkBusy = signal(false);
  private readonly deviceSettings = signal(this.getSettings('device'));
  private readonly titleSettings = linkedSignal(() => {
    const key = this.readingKey();
    return key ? this.getSettings(this.progress.id(key), true) : null;
  });
  protected readonly settings = computed(() => this.titleSettings() ?? this.deviceSettings()!);
  protected readonly overrideSettings = computed(() => this.titleSettings() !== null);
  protected readonly paged = computed(() => this.settings().mode === 'paged');
  protected readonly pageShown = linkedSignal(() => { this.request(); this.page(); return 0; });
  protected readonly targetPage = computed(() => {
    const chapter = this.chapterContent();
    if (!chapter) return 0;
    const requested = Number(this.page());
    if (Number.isInteger(requested) && requested > 0) return Math.min(requested - 1, chapter.pageCount - 1);
    const state = this.reading.hasValue() ? this.reading.value() : undefined;
    const resume = state?.resume;
    const stored = resume?.chapterId === chapter.chapterId && resume.language === chapter.language
      ? resume.pageIndex : state?.chapters.find(row => row.chapterId === chapter.chapterId && row.language === chapter.language)?.pageIndex;
    return Math.min(stored ?? 0, chapter.pageCount - 1);
  });
  private readonly restored = linkedSignal(() => { this.request(); this.page(); return false; });
  private revisionOverride?: string;
  private awaitingInteraction = false;
  private observation?: {element: HTMLElement; index: number; since: number; endSince?: number};
  private lastStored?: {index: number; time: number; end: boolean};
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
  constructor() {
    effect(cleanup => {
      this.request();
      this.page();
      this.revisionOverride = undefined;
      this.observation = undefined;
      this.lastStored = undefined;
      cleanup(() => this.progress.flush());
    });
    effect(cleanup => {
      if (!this.reading.hasValue() || this.restored()) return;
      const target = this.targetPage();
      const pages = this.images();
      if (!pages[target]) return;
      const render = afterNextRender(() => {
        this.pageShown.set(target);
        if (target > 0 && !this.paged()) {
          const pageTop = pages[target].nativeElement.getBoundingClientRect().top + window.scrollY;
          const headerHeight = this.bar()?.nativeElement.offsetHeight ?? 0;
          window.scrollTo({top: pageTop - headerHeight - 8});
        } else {
          window.scrollTo({top: 0});
        }
        this.current.set(target);
        this.awaitingInteraction = target > 0;
        this.observation = undefined;
        this.restored.set(true);
      }, {injector: this.injector});
      cleanup(() => render.destroy());
    });
    const timer = window.setInterval(() => this.observeReading(), 250);
    this.destroy.onDestroy(() => window.clearInterval(timer));
  }

  protected markReady(event: {index: number; loaded: boolean}) {
    this.readyImages.update(previous => new Map(previous).set(event.index, event.loaded));
  }

  protected resetImage(index: number) {
    this.readyImages.update(previous => {
      if (!previous.has(index)) return previous;
      const next = new Map(previous);
      next.delete(index);
      return next;
    });
    if (this.observation?.index === index) this.observation = undefined;
  }

  protected async resolveProgress(local: boolean) {
    const key = this.readingKey();
    if (!key) return;
    try {
      const state = await this.progress.takeOver(key, local && !this.saveFeedback()?.refresh);
      this.revisionOverride = state.revision;
      if (local) this.retryContent();
      else if (state.resume) {
        const resume = state.resume;
        await this.router.navigate(readerLink(key, resume.chapterId, resume.language), {
          queryParams: {page: resume.pageIndex + 1, downloadId: resume.downloadId},
          replaceUrl: true,
        });
        this.restored.set(false);
        this.reading.reload();
      }
      else {
        this.restored.set(false);
        this.observation = undefined;
        this.lastStored = undefined;
        this.reading.reload();
      }
    } catch { this.reading.reload(); }
  }

  protected readingInteraction() {
    if (this.restored()) this.awaitingInteraction = false;
  }

  protected readingKeydown(event: KeyboardEvent) {
    if (!this.settings().keys || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey
        || this.settingsOpen() || this.dialog().nativeElement.open
        || (event.target instanceof HTMLElement && event.target.closest('input,select,textarea,button,a,[contenteditable]'))) return;
    if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) this.readingInteraction();
    if (event.key.toLowerCase() === 'h') this.panelsHidden.update(hidden => !hidden);
    if (this.paged() && ['ArrowLeft','ArrowRight','PageUp','PageDown'].includes(event.key)) {
      event.preventDefault();
      const forward = event.key === 'PageDown' || event.key === (this.settings().rtl ? 'ArrowLeft' : 'ArrowRight');
      this.changePage(forward ? 1 : -1);
    }
  }

  protected changePage(step: number) {
    const chapter = this.chapterContent();
    if (!chapter) return;
    const next = Math.max(0,Math.min(chapter.pageCount - 1,this.pageShown() + step));
    this.readingInteraction();
    this.pageShown.set(next);
    this.current.set(next);
    this.observation = undefined;
    window.scrollTo({top: 0});
  }

  protected bookmarkPage() {
    const chapter = this.chapterContent();
    const key = this.readingKey();
    if (!chapter || !key || this.bookmarkBusy()) return;
    const page = this.paged() ? this.pageShown() : this.current();
    if (!this.readyImages().get(page)) {
      this.bookmarkStatus.set('Дождитесь загрузки страницы.');
      return;
    }
    this.bookmarkBusy.set(true);
    this.api.chapterActions(key,'bookmark',[{chapterId:chapter.chapterId,language:chapter.language,pageIndex:page}])
      .subscribe({
        next: results => { this.bookmarkBusy.set(false); this.bookmarkStatus.set(results[0].detail ?? 'Закладка сохранена'); },
        error: error => { this.bookmarkBusy.set(false); this.bookmarkStatus.set(problem(error).detail); },
      });
  }

  protected pagePermitted(index: number) {
    if (!this.paged()) {
      if (!this.restored()) return index === this.targetPage();
      const current = this.current();
      return index === current || index >= current - 1 && index < current ||
        this.readyImages().get(current) === true && index > current && index <= current + this.settings().prefetch;
    }
    const current = this.pageShown();
    return index === current || this.readyImages().get(current) === true &&
      index > current && index <= current + this.settings().prefetch;
  }

  protected pageEager(index: number) {
    if (this.paged()) return this.pagePermitted(index);
    const current = this.restored() ? this.current() : this.targetPage();
    return this.restored() ? index === current ||
      this.readyImages().get(current) === true && index > current && index <= current + this.settings().prefetch
      : index === current;
  }

  protected updateSettings(patch: Partial<ReaderSettings>, perTitle = this.overrideSettings()) {
    const old = this.paged();
    const position = this.current();
    const next = {...this.settings(),...patch};
    const key = this.readingKey();
    if (perTitle && key) {
      this.titleSettings.set(next);
      this.putSettings(this.progress.id(key),next);
    } else {
      this.deviceSettings.set(next);
      this.putSettings('device',next);
    }
    if (old !== this.paged()) this.keepPage(position);
  }

  protected toggleTitleSettings(enabled: boolean) {
    const key = this.readingKey();
    if (!key) return;
    if (enabled) this.updateSettings({},true);
    else {
      const position = this.current();
      this.titleSettings.set(null);
      this.putSettings(this.progress.id(key),null);
      this.keepPage(position);
    }
  }

  private keepPage(position: number) {
    this.pageShown.set(position);
    this.observation = undefined;
    this.awaitingInteraction = true;
    afterNextRender(() => {
      if (this.paged()) window.scrollTo({top: 0});
      else this.images()[position]?.nativeElement.scrollIntoView({block:'start'});
    }, {injector:this.injector});
  }

  private getSettings(key: string, optional = false): ReaderSettings | null {
    try {
      const raw = localStorage.getItem('kadr-reader/' + key);
      if (!raw) return optional ? null : DEFAULT_SETTINGS;
      const settings = JSON.parse(raw);
      return {mode: settings.mode === 'paged' ? 'paged' : 'vertical',
        width: [560,760,960,1200].includes(settings.width) ? settings.width : 760, rtl: settings.rtl === true,
        keys: settings.keys !== false, prefetch: [0,1,2].includes(settings.prefetch) ? settings.prefetch : 1};
    } catch { return optional ? null : DEFAULT_SETTINGS; }
  }

  private putSettings(key: string, settings: ReaderSettings | null) {
    try {
      if (settings) localStorage.setItem('kadr-reader/' + key,JSON.stringify(settings));
      else localStorage.removeItem('kadr-reader/' + key);
    } catch { /* Settings remain active in the current reader. */ }
  }

  private observeReading() {
    const key = this.readingKey();
    const chapter = this.chapterContent();
    if (!key || !chapter || !this.reading.hasValue() || !this.restored() || document.hidden
        || this.awaitingInteraction || this.settingsOpen() || this.dialog().nativeElement.open || this.saveFeedback()?.conflict) {
      this.observation = undefined;
      return;
    }
    const top = this.bar()?.nativeElement.getBoundingClientRect().bottom ?? 0;
    const bottom = window.innerHeight;
    const line = top + Math.min(112, (bottom - top) * 0.18);
    // Pick the page in the reading area before checking load state. A failed page
    // cannot promote a neighboring prefetched image to the current reading position.
    let candidate: {element: HTMLElement; index: number; area: DOMRect; distance: number} | undefined;
    for (const [index, page] of this.images().entries()) {
      const element = page.nativeElement;
      if (this.paged() && index !== this.pageShown()) continue;
      const area = element.getBoundingClientRect();
      if (area.bottom <= top || area.top >= bottom) continue;
      const distance = Math.max(area.top - line, line - area.bottom, 0);
      if (!candidate || distance < candidate.distance) candidate = {element, index, area, distance};
    }
    const image = candidate?.element.querySelector('img');
    if (!candidate || !this.readyImages().get(candidate.index) || !image?.naturalWidth) {
      this.observation = undefined;
      return;
    }
    const area = image.getBoundingClientRect();
    const visible = Math.min(area.bottom, bottom) - Math.max(area.top, top);
    if (visible < 0.45 * Math.min(area.height, bottom - top) || area.height <= 0) {
      this.observation = undefined;
      return;
    }
    const now = performance.now();
    const {index, element} = candidate;
    if (this.observation?.element !== element) this.observation = {element, index, since: now};
    const seen = this.observation;
    if (index === chapter.pageCount - 1 && area.bottom <= bottom && area.bottom > top) seen.endSince ??= now;
    else seen.endSince = undefined;
    if (now - seen.since < 1000) return;
    const reachedEnd = seen.endSince !== undefined && now - seen.endSince >= 2000;
    const last = this.lastStored;
    if (last?.index === index && (!reachedEnd || last.end) && now - last.time < 5000) return;
    const seconds = Math.min(5, Math.floor((now - Math.max(last?.time ?? 0, seen.since)) / 1000));
    this.lastStored = {index, time: now, end: reachedEnd};
    this.current.set(index);
    this.progress.record(key, this.revisionOverride ?? this.reading.value().revision, {
      chapterId: chapter.chapterId, language: chapter.language, pageIndex: index,
      downloadId: this.downloadId() ?? null, manifestVersion: chapter.manifestVersion ?? null,
      seenPageIndexes: [index], reachedEnd, activeSecondsDelta: seconds,
    });
  }

  protected syncPosition() {
    if (this.paged()) return;
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
