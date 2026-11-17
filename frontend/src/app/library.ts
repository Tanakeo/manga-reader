import { Component, computed, DestroyRef, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideArrowRight as ArrowRight, LucideCheck as Check, LucideDynamicIcon, LucidePencil as Pencil, LucideTrash2 as Trash2 } from '@lucide/angular';
import { finalize } from 'rxjs';
import {
  Api, fieldError, languageName, LibraryEntry, LibraryUpdate, Problem, problem, readerLink,
  ReadingStatus, statusOptions, statuses, titleLink,
} from './api';
import { Cover, State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { DownloadStore } from './downloads';

@Component({
  selector: 'app-library-editor',
  imports: [FormsModule, State],
  templateUrl: './library-editor.html',
})
export class LibraryEditor {
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  readonly entry = input.required<LibraryEntry>();
  readonly saved = output<LibraryEntry>();
  readonly cancelled = output<void>();
  readonly saving = output<boolean>();
  protected readonly status = signal<ReadingStatus>('PLANNED');
  protected readonly note = signal('');
  protected readonly chapterId = signal('');
  protected readonly language = signal('');
  protected readonly pageNumber = signal<number | null>(1);
  protected readonly mode = signal<'keep' | 'update' | 'clear'>('keep');
  protected readonly pending = signal(false);
  protected readonly issue = signal<Problem | undefined>(undefined);
  protected readonly invalidPage = signal(false);
  protected readonly options = statusOptions;
  protected readonly problem = problem;
  protected readonly lang = languageName;
  protected readonly chapters = rxResource({
    params: () => this.mode() === 'update' ? this.entry().title : undefined,
    stream: ({params}) => this.api.chapters(params),
  });
  protected readonly chapter = computed(() => this.chapters.hasValue()
    ? this.chapters.value().find(chapter => chapter.id === this.chapterId())
    : undefined);
  protected readonly progressIncomplete = computed(() => this.mode() === 'update'
    && (!this.chapter() || !this.chapter()?.languages.includes(this.language())));

  ngOnInit() {
    const entry = this.entry();
    this.status.set(entry.status);
    this.note.set(entry.note ?? '');
    this.chapterId.set(entry.progress?.chapterId ?? '');
    this.language.set(entry.progress?.language ?? '');
    this.pageNumber.set((entry.progress?.pageIndex ?? 0) + 1);
  }

  protected setChapter(chapterId: string) {
    this.chapterId.set(chapterId);
    const languages = this.chapter()?.languages ?? [];
    this.language.set(languages.includes('ru') ? 'ru' : languages[0] ?? '');
    this.pageNumber.set(1);
  }

  protected errorFor(field: string) {
    if (field === 'progress.pageIndex' && this.invalidPage()) return 'Введите целый номер страницы от 1.';
    if (field === 'note' && this.note().length > 2000) return 'Введите не более 2000 символов.';
    return fieldError(this.issue(), field);
  }

  protected save() {
    if (this.pending() || this.progressIncomplete()) return;
    this.issue.set(undefined);
    const current = this.entry();
    const update: LibraryUpdate = {};
    const note = this.note().trim() ? this.note() : null;
    const page = this.pageNumber();
    this.invalidPage.set(this.mode() === 'update' && (page === null || !Number.isInteger(page) || page < 1));
    if (this.invalidPage() || this.note().length > 2000) return;

    if (this.status() !== current.status) update.status = this.status();
    if (note !== current.note) update.note = note;
    if (this.mode() === 'clear') update.progress = null;
    if (this.mode() === 'update' && page !== null) {
      update.progress = {chapterId: this.chapterId(), language: this.language(), pageIndex: page - 1};
    }
    if (!Object.keys(update).length) { this.cancelled.emit(); return; }

    this.pending.set(true);
    this.saving.emit(true);
    this.api.update(current.id, update).pipe(
      takeUntilDestroyed(this.destroy),
      finalize(() => {
        this.pending.set(false);
        this.saving.emit(false);
      }),
    ).subscribe({
      next: entry => this.saved.emit(entry),
      error: error => this.issue.set(problem(error)),
    });
  }
}

@Component({
  imports: [RouterLink, LucideDynamicIcon, Cover, State, LibraryEditor, LocalizedTitle],
  templateUrl: './library.html',
})
export class Library {
  protected readonly titleLanguage = inject(TitleLocale).language;
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  protected readonly entries = rxResource({stream: () => this.api.library()});
  protected readonly downloads = new DownloadStore();
  protected readonly editing = signal<string | undefined>(undefined);
  protected readonly editingBusy = signal(false);
  protected readonly removing = signal<LibraryEntry | undefined>(undefined);
  protected readonly pending = signal(false);
  protected readonly deleteError = signal<Problem | undefined>(undefined);
  protected readonly notice = signal('');
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('deleteDialog');
  private readonly heading = viewChild.required<ElementRef<HTMLHeadingElement>>('heading');
  private editTrigger?: HTMLButtonElement;
  protected readonly problem = problem;
  protected readonly titleLink = titleLink;
  protected readonly readerLink = readerLink;
  protected readonly statuses = statuses;
  protected readonly lang = languageName;
  protected readonly arrow = ArrowRight;
  protected readonly check = Check;
  protected readonly pencil = Pencil;
  protected readonly trash = Trash2;

  protected toggleEdit(id: string, trigger: HTMLButtonElement) {
    if (this.editingBusy()) return;
    if (this.editing() === id) {
      this.closeEdit();
      return;
    }
    this.editTrigger = trigger;
    this.notice.set('');
    this.editing.set(id);
  }

  protected closeEdit() {
    this.editing.set(undefined);
    this.editTrigger?.focus();
  }

  protected apply(entry: LibraryEntry) {
    this.entries.update(entries => [entry, ...(entries ?? []).filter(item => item.id !== entry.id)]);
    this.closeEdit();
    this.notice.set('Изменения сохранены.');
  }

  protected openDelete(entry: LibraryEntry) {
    if (this.editingBusy()) return;
    this.notice.set('');
    this.deleteError.set(undefined);
    this.removing.set(entry);
    this.dialog().nativeElement.showModal();
  }

  protected closeDelete() {
    if (this.pending()) return;
    this.dialog().nativeElement.close();
  }

  protected cancelDelete(event: Event) {
    if (this.pending()) event.preventDefault();
  }

  protected remove() {
    const target = this.removing();
    if (!target || this.pending()) return;
    this.deleteError.set(undefined);
    this.pending.set(true);
    this.api.delete(target.id).pipe(
      takeUntilDestroyed(this.destroy),
      finalize(() => this.pending.set(false)),
    ).subscribe({
      next: () => {
        this.dialog().nativeElement.close();
        this.entries.update(entries => (entries ?? []).filter(item => item.id !== target.id));
        if (this.editing() === target.id) this.editing.set(undefined);
        this.notice.set('Тайтл удалён из библиотеки.');
        this.heading().nativeElement.focus();
      },
      error: error => this.deleteError.set(problem(error)),
    });
  }
}
