import { Component, computed, DestroyRef, ElementRef, inject, input, linkedSignal, signal, viewChild } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { LucideArrowLeft as ArrowLeft, LucideArrowRight as ArrowRight, LucideArrowUpRight as ArrowUpRight, LucideBookOpen as BookOpen, LucideCheck as Check, LucideChevronRight as ChevronRight, LucideDynamicIcon, LucidePlus as Plus } from '@lucide/angular';
import { finalize } from 'rxjs';
import {
  Api, Chapter, ChapterAction, ChapterBookmark, fieldError, languageName, LibraryEntry, libraryStatusParams, Problem, problem, readerLink,
  ReadingStatus, sameTitle, statusOptions, statuses, TitleKey,
} from './api';
import { catalogParams, readCatalog, tagName } from './catalog-query';
import { Cover, State } from './common';
import { chapterLabel, downloadState, DownloadStore, offlineLink } from './downloads';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { MagneticButton } from './magnetic-button';
import { TitleDetails } from './shared/title-details';
import { TitlePreviewState } from './shared/title-preview-state';
import { CatalogFacts } from './catalog-facts';

@Component({
  selector: 'app-add-entry',
  imports: [ReactiveFormsModule, LucideDynamicIcon, RouterLink],
  template: `
    <h2>Добавить в библиотеку</h2>
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-busy]="pending()">
      <div class="field">
        <label for="add-status">Статус</label>
        <select id="add-status" formControlName="status" [attr.aria-invalid]="!!errorFor('status')"
          aria-describedby="add-status-error">
          @for (option of options; track option[0]) { <option [value]="option[0]">{{ option[1] }}</option> }
        </select>
        <span id="add-status-error" class="field-error">{{ errorFor('status') }}</span>
      </div>
      <div class="field">
        <label for="add-note">Заметка <small>необязательно</small></label>
        <textarea id="add-note" formControlName="note" rows="3" maxlength="2000" placeholder="Например, с какой главы начать"
          [attr.aria-invalid]="!!errorFor('note')" aria-describedby="add-note-error"></textarea>
        <span id="add-note-error" class="field-error">{{ errorFor('note') }}</span>
      </div>
      <button type="submit" class="button primary add-action" [class.confirmed]="saved()"
        [attr.aria-disabled]="pending() || !!saved()">
        @if (pending()) { <span class="spinner" aria-hidden="true"></span> Сохраняем… }
        @else if (saved()) { <svg class="icon" [lucideIcon]="check" [size]="18" aria-hidden="true"/> Добавлено }
        @else { <svg class="icon" [lucideIcon]="plus" [size]="18" aria-hidden="true"/> Добавить в библиотеку }
      </button>
      <div aria-live="polite">
        @if (saved(); as entry) {
          <p class="feedback">Тайтл добавлен. <a routerLink="/library"
            [queryParams]="libraryStatusParams(entry.status)">Открыть библиотеку</a></p>
        }
      </div>
      @if (issue(); as issue) {
        <p class="feedback error" role="alert">{{ issue.detail }}
          @if (issue.code === 'DUPLICATE_LIBRARY_ENTRY') { <a routerLink="/library">Открыть библиотеку</a> }
        </p>
      }
    </form>
  `,
})
export class AddEntry {
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  readonly title = input.required<TitleKey>();
  protected readonly pending = signal(false);
  protected readonly saved = signal<LibraryEntry | undefined>(undefined);
  protected readonly issue = signal<Problem | undefined>(undefined);
  protected readonly options = statusOptions;
  protected readonly check = Check;
  protected readonly plus = Plus;
  protected readonly libraryStatusParams = libraryStatusParams;
  protected readonly form = new FormGroup({
    status: new FormControl<ReadingStatus>('PLANNED', {nonNullable: true, validators: Validators.required}),
    note: new FormControl('', {nonNullable: true, validators: Validators.maxLength(2000)}),
  });

  protected errorFor(field: 'status' | 'note') {
    const control = this.form.controls[field];
    if (control.touched && control.hasError('required')) return 'Выберите статус.';
    if (control.touched && control.hasError('maxlength')) return 'Введите не более 2000 символов.';
    return fieldError(this.issue(), field);
  }

  protected submit() {
    if (this.pending() || this.saved()) return;
    this.issue.set(undefined);
    this.form.markAllAsTouched();
    if (this.form.invalid) return;

    const {status, note} = this.form.getRawValue();
    this.pending.set(true);
    this.form.disable();
    this.api.add(this.title(), status, note.trim() ? note : null).pipe(
      takeUntilDestroyed(this.destroy),
      finalize(() => {
        this.pending.set(false);
        if (!this.saved()) this.form.enable();
      }),
    ).subscribe({
      next: entry => this.saved.set(entry),
      error: error => this.issue.set(problem(error)),
    });
  }
}

@Component({
  imports: [RouterLink, LucideDynamicIcon, Cover, State, AddEntry, LocalizedTitle, MagneticButton, CatalogFacts],
  selector: 'app-title-page',
  templateUrl: './title.html',
})
export class TitlePage {
  private readonly destroy = inject(DestroyRef);
  private readonly details = inject(TitleDetails);
  private readonly preview = inject(TitlePreviewState);
  private readonly router = inject(Router);
  protected readonly titleLanguage = inject(TitleLocale).language;
  protected readonly libraryStatusParams = libraryStatusParams;
  private readonly api = inject(Api);
  readonly sourceId = input.required<string>();
  readonly externalTitleId = input.required<string>();
  private readonly key = computed(() => ({sourceId: this.sourceId(), externalTitleId: this.externalTitleId()}));
  private readonly catalogContext = toSignal(inject(ActivatedRoute).queryParamMap, {requireSync: true});
  protected readonly backParams = computed(() => this.catalogContext().has('fromCatalog')
    ? catalogParams(readCatalog(this.catalogContext(), 'catalogSource')) : {sourceId: this.sourceId()});
  protected readonly downloads = new DownloadStore(this.key);
  protected readonly downloadSupported = computed(() => this.downloads.data.hasValue() && this.downloads.data.value().supported);
  protected readonly choosing = signal<Chapter | undefined>(undefined);
  private readonly downloadDialog = viewChild.required<ElementRef<HTMLDialogElement>>('downloadDialog');
  protected readonly offlineLink = offlineLink;
  protected readonly downloadState = downloadState;
  protected readonly chapterLabel = chapterLabel;
  protected readonly tagName = tagName;
  protected readonly title = rxResource({params: this.key, stream: ({params}) => this.details.load(params)});
  protected readonly displayTitle = computed(() => {
    if (this.title.hasValue()) return this.title.value();
    const summary = this.details.summary(this.key());
    return summary ? {...summary, description: null, sourceUrl: '', license: null, tags: []} : undefined;
  });
  protected readonly arriving = computed(() => {
    const selected = this.preview.selection();
    return this.preview.openingPage() && !!selected && sameTitle(selected.key, this.key());
  });
  protected readonly backHref = computed(() => this.preview.pageReturn()?.url ??
    this.router.serializeUrl(this.router.createUrlTree(['/catalog'], {queryParams: this.backParams()})));
  protected readonly backLabel = computed(() => this.preview.pageReturn()?.url.startsWith('/library') ? 'Библиотека' : 'Каталог');
  protected readingState() {return {kadrTitleReturn:this.preview.pageReturn()};}
  protected readonly chapterLanguage = computed(() => {
    const languages = this.catalogContext().getAll('chapterLanguage');
    return languages.length === 1 ? languages[0] : '';
  });
  protected readonly chapters = rxResource({params: () => ({key: this.key(), language: this.chapterLanguage()}),
    stream: ({params}) => this.api.chapters(params.key, params.language)});
  protected readonly chapterCheck = computed(() => this.api.chapterAvailability(this.key(), this.chapterLanguage()));
  protected setChapterLanguage(language: string) {
    void this.router.navigate([], {queryParams: {chapterLanguage: language || null}, queryParamsHandling: 'merge',state:this.readingState()});
  }
  protected readonly library = rxResource({stream: () => this.api.library()});
  protected readonly entry = computed(() => this.library.hasValue()
    ? this.library.value().find(entry => sameTitle(entry.title, this.key()))
    : undefined);
  protected readonly availableLanguages = computed(() => this.chapters.hasValue()
    ? [...new Set(this.chapters.value().flatMap(chapter => chapter.languages))]
    : []);
  protected readonly reading = rxResource({params: () => this.title.hasValue() ? this.key() : undefined,
    stream: ({params}) => this.api.reading(params)});
  protected readonly resume = computed(() => this.reading.hasValue() ? this.reading.value().resume : null);
  protected readonly readChapters = computed(() => new Set(this.reading.hasValue() ? this.reading.value().chapters
    .filter(chapter => chapter.isRead).map(chapter => chapter.chapterId + '/' + chapter.language) : []));
  protected readonly readingIssue = signal('');
  protected readonly readingBusy = signal(false);
  protected readonly bookmarks = rxResource({params: () => this.title.hasValue() ? this.key() : undefined,
    stream: ({params}) => this.api.bookmarks(params)});
  protected readonly selectedEditions = linkedSignal(() => { this.key(); return new Map<string,ChapterBookmark>(); });
  protected readonly batchFeedback = signal<string[]>([]);
  protected readonly batchMode = signal(false);
  protected readonly batchBusy = signal(false);

  protected editionSelected(chapter:string,language:string) {
    return this.selectedEditions().has(chapter + '/' + language);
  }

  protected selectEdition(chapterId:string,language:string,checked:boolean) {
    this.selectedEditions.update(current => {
      const next = new Map(current);
      const key = chapterId + '/' + language;
      if (checked && next.size < 100) next.set(key,{chapterId,language,pageIndex:null});
      else if (!checked) next.delete(key);
      return next;
    });
  }

  protected runBatch(action:ChapterAction) {
    if (!this.selectedEditions().size || this.batchBusy()) return;
    this.batchBusy.set(true);
    this.batchFeedback.set([]);
    this.api.chapterActions(this.key(),action,[...this.selectedEditions().values()])
      .pipe(takeUntilDestroyed(this.destroy),finalize(() => this.batchBusy.set(false))).subscribe({
        next: rows => {
          this.batchFeedback.set(['Выполнено: ' + rows.filter(row => row.success).length + ' из ' + rows.length,
            ...rows.filter(row => !row.success).map(row => row.item.chapterId + ' · ' + row.item.language + ' — ' + row.detail)]);
          for (const row of rows) if (row.success) this.selectEdition(row.item.chapterId,row.item.language,false);
          this.reading.reload();
          this.bookmarks.reload();
          if (action === 'download') this.downloads.data.reload();
        },
        error: error => this.batchFeedback.set([problem(error).detail]),
      });
  }

  protected markRead(chapter: string, language: string) {
    if (!this.reading.hasValue() || this.readingBusy()) return;
    this.readingBusy.set(true);
    this.readingIssue.set('');
    const key = this.key();
    this.api.markReading(key, chapter, language, !this.readChapters().has(chapter + '/' + language), this.reading.value().revision)
      .subscribe({
        next: () => { this.readingBusy.set(false); if (sameTitle(key, this.key())) this.reading.reload(); },
        error: error => {
          this.readingBusy.set(false);
          if (!sameTitle(key, this.key())) return;
          this.readingIssue.set(problem(error).detail);
          this.reading.reload();
        },
      });
  }

  protected readonly firstChapter = computed(() => this.chapters.hasValue()
    ? this.chapters.value().find(chapter => chapter.languages.length)
    : undefined);
  protected readonly problem = problem;
  protected readonly back = ArrowLeft;
  protected readonly arrow = ArrowRight;
  protected readonly external = ArrowUpRight;
  protected readonly chevron = ChevronRight;
  protected readonly book = BookOpen;
  protected readonly check = Check;
  protected readonly lang = languageName;
  protected readonly readerLink = readerLink;
  protected readonly statuses = statuses;

  protected defaultLanguage(languages: string[]) {
    return languages.includes('ru') ? 'ru' : languages[0];
  }

  protected goBack(event: MouseEvent) {
    if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    if (this.preview.pageReturn()) this.preview.backToList(event);
    else {
      event.preventDefault();
      void this.router.navigateByUrl(this.backHref());
    }
  }

  protected chooseDownload(chapter: Chapter) {
    this.downloads.issue.set('');
    if (chapter.languages.length === 1 && !this.downloads.find(chapter.id, chapter.languages[0])) {
      this.downloads.start(chapter.id, chapter.languages[0]);
      return;
    }
    this.choosing.set(chapter);
    this.downloadDialog().nativeElement.showModal();
  }

  protected closeDownload() {
    this.downloadDialog().nativeElement.close();
  }
}
