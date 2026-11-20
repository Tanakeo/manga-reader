import { NgTemplateOutlet } from '@angular/common';
import { afterNextRender, Component, computed, DestroyRef, ElementRef, inject, Injector, input, output, signal, viewChild } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { LucideArrowRight as ArrowRight, LucideBookOpen as BookOpen, LucideChevronDown as ChevronDown, LucideDynamicIcon, LucideEllipsis as Ellipsis, LucidePlus as Plus } from '@lucide/angular';
import { finalize, Observable } from 'rxjs';
import {
  Api, fieldError, languageName, LibraryEntry, LibrarySection, libraryStatusParams, LibraryUpdate, Problem, problem, readerLink,
  ReadingStatus, statusOptions, statuses, titleLink,
} from './api';
import { Cover, State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { DownloadsPage, DownloadStore } from './downloads';
import { MagneticButton } from './magnetic-button';
import { CommandSearch } from './shared/command-search';
import { TitlePreviewLink } from './shared/title-preview-link';

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

type LibraryDialog =
  | {kind: 'create'}
  | {kind: 'rename' | 'pick' | 'delete-section'; section: LibrarySection}
  | {kind: 'edit' | 'delete-entry'; entry: LibraryEntry}
  | {kind: 'remove-entry'; section: LibrarySection; entry: LibraryEntry};

const statusSections = (Object.keys(statuses) as ReadingStatus[]).map(status =>
  ({status, key: libraryStatusParams(status).section, name: statuses[status]}));

@Component({
  imports: [FormsModule, NgTemplateOutlet, RouterLink, LucideDynamicIcon, Cover, State, LibraryEditor, LocalizedTitle, MagneticButton, DownloadsPage, CommandSearch, TitlePreviewLink],
  templateUrl: './library.html',
  styleUrl: './library.css',
  host: {'(document:click)': 'dismissMenus($event)', '(keydown.escape)': 'escapeMenu($event)'},
})
export class Library {
  protected readonly titleLanguage = inject(TitleLocale).language;
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly parameters = toSignal(inject(ActivatedRoute).queryParamMap, {requireSync: true});
  private readonly requested = computed(() => this.parameters().get('section') ?? '');
  protected readonly searchQuery = computed(() => (this.parameters().get('q') ?? '').trim().slice(0, 120));
  protected readonly entries = rxResource({stream: () => this.api.library()});
  protected readonly sections = rxResource({stream: () => this.api.librarySections()});
  protected readonly downloads = new DownloadStore();
  protected readonly allEntries = computed(() => this.entries.hasValue() ? this.entries.value() : []);
  private readonly allSections = computed(() => this.sections.hasValue() ? this.sections.value() : []);
  protected readonly customSections = computed(() => this.allSections().filter(section => !section.systemKey));
  protected readonly builtIn = computed(() => this.statusNav().find(section => section.key === this.requested()));
  protected readonly isDownloads = computed(() => this.requested() === 'downloads');
  protected readonly custom = computed(() => this.customSections().find(section => section.id === this.requested()));
  protected readonly activeKey = computed(() => this.isDownloads() ? 'downloads' : this.builtIn()?.key ?? this.custom()?.id ?? '');
  protected readonly currentSection = computed(() => this.allSections().find(section =>
    section.systemKey === this.activeKey() || section.id === this.activeKey()));
  protected readonly downloadsName = computed(() => this.allSections().find(section => section.systemKey === 'downloads')?.name ?? 'Скачанное');
  protected readonly unresolved = computed(() => !!this.requested() && !this.builtIn() && !this.isDownloads() && !this.sections.hasValue());
  protected readonly sectionTitle = computed(() => this.currentSection()?.name ?? this.builtIn()?.name ??
    (this.isDownloads() ? this.downloadsName() : this.unresolved() ? 'Библиотека' : 'Все тайтлы'));
  protected readonly missingSection = computed(() => !!this.requested() && !this.activeKey() && this.sections.hasValue());
  protected readonly statusNav = computed(() => {
    const counts: Record<ReadingStatus, number> = {PLANNED: 0, READING: 0, COMPLETED: 0};
    for (const entry of this.allEntries()) counts[entry.status]++;
    return statusSections.map(section => ({...section,
      name: this.allSections().find(item => item.systemKey === section.key)?.name ?? section.name,
      count: counts[section.status]}));
  });
  protected readonly indicatorRow = computed(() => {
    const builtIn = statusSections.findIndex(section => section.key === this.activeKey());
    if (builtIn >= 0) return builtIn + 1;
    if (this.isDownloads()) return statusSections.length + 1;
    const custom = this.customSections().findIndex(section => section.id === this.activeKey());
    return custom >= 0 ? custom + statusSections.length + 2 : 0;
  });
  private readonly sectionEntries = computed(() => {
    const status = this.builtIn()?.status;
    const custom = this.custom();
    if (status) return this.allEntries().filter(entry => entry.status === status);
    if (custom) {
      const members = new Set(custom.entryIds);
      return this.allEntries().filter(entry => members.has(entry.id));
    }
    return this.allEntries();
  });
  protected readonly visibleEntries = computed(() => {
    const query = this.searchQuery().toLocaleLowerCase();
    return this.sectionEntries().filter(entry => !query ||
      [entry.title.title, entry.title.titleRu, entry.title.titleEn].some(name => name?.toLocaleLowerCase().includes(query)));
  });
  private readonly entryIds = computed(() => new Set(this.allEntries().map(entry => entry.id)));

  protected readonly panel = signal<LibraryDialog | undefined>(undefined);
  protected readonly name = signal('');
  protected readonly pickerSearch = signal('');
  protected readonly checked = signal(new Set<string>());
  protected readonly pending = signal(false);
  protected readonly editingBusy = signal(false);
  protected readonly issue = signal<Problem | undefined>(undefined);
  protected readonly notice = signal('');
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('libraryDialog');
  private readonly heading = viewChild.required<ElementRef<HTMLHeadingElement>>('heading');
  private dialogTrigger?: HTMLElement;

  protected readonly pickable = computed(() => {
    const panel = this.panel();
    if (panel?.kind !== 'pick') return [];
    const existing = new Set(panel.section.entryIds);
    return this.allEntries().filter(entry => !existing.has(entry.id));
  });
  protected readonly pickerMatches = computed(() => {
    const query = this.pickerSearch().trim().toLowerCase();
    return this.pickable().filter(({title}) => [title.title, title.titleRu, title.titleEn, title.sourceName]
      .some(value => value?.toLowerCase().includes(query)));
  });
  protected readonly busy = computed(() => this.pending() || this.editingBusy());
  protected readonly nameError = computed(() => fieldError(this.issue(), 'name') ??
    (this.issue()?.code === 'LIBRARY_SECTION_NAME_EXISTS' ? this.issue()?.detail : undefined));
  protected readonly dialogTitle = computed(() => {
    switch (this.panel()?.kind) {
      case 'create': return 'Создать секцию';
      case 'rename': return 'Переименовать секцию';
      case 'pick': return 'Добавить тайтлы';
      case 'edit': return 'Редактировать запись';
      case 'delete-section': return 'Удалить секцию?';
      case 'remove-entry': return 'Убрать тайтл из секции?';
      case 'delete-entry': return 'Удалить из библиотеки?';
      default: return '';
    }
  });

  protected readonly problem = problem;
  protected readonly titleLink = titleLink;
  protected readonly readerLink = readerLink;
  protected readonly statuses = statuses;
  protected readonly lang = languageName;
  protected readonly arrow = ArrowRight;
  protected readonly book = BookOpen;
  protected readonly chevron = ChevronDown;
  protected readonly more = Ellipsis;
  protected readonly plus = Plus;

  protected sectionParams(key: string) {
    return {section: key || null, q: this.searchQuery() || null};
  }

  protected setSearch(query: string) {
    void this.router.navigate(['/library'], {queryParams: {...this.sectionParams(this.activeKey()), q: query.trim() || null}});
  }

  protected memberCount(section: LibrarySection) {
    return section.entryIds.filter(id => this.entryIds().has(id)).length;
  }

  protected dismissMenus(event: MouseEvent) {
    if (!(event.target instanceof Node)) return;
    for (const menu of this.openMenus()) {
      if (!menu.contains(event.target)) menu.open = false;
    }
  }

  protected escapeMenu(event: Event) {
    const menu = this.openMenus().find(candidate =>
      event.target instanceof Node && candidate.contains(event.target));
    if (!menu) return;
    event.preventDefault();
    menu.open = false;
    menu.querySelector('summary')?.focus();
  }

  protected closeMenus() {
    for (const menu of this.openMenus()) menu.open = false;
  }

  protected selectSection(event: MouseEvent) {
    if (event.currentTarget instanceof HTMLElement) {
      event.currentTarget.closest('.library-mobile-switcher')?.querySelector('summary')?.focus();
    }
    this.closeMenus();
  }

  protected open(panel: LibraryDialog, event: Event) {
    if (this.busy()) return;
    if (event.currentTarget instanceof HTMLElement) {
      const menu = event.currentTarget.closest<HTMLDetailsElement>('.library-disclosure');
      this.dialogTrigger = menu?.querySelector('summary') ?? event.currentTarget;
      this.dialogTrigger.focus({preventScroll: true});
    }
    this.closeMenus();
    this.notice.set('');
    this.issue.set(undefined);
    this.name.set(panel.kind === 'rename' ? panel.section.name : '');
    this.pickerSearch.set('');
    this.checked.set(new Set());
    this.panel.set(panel);
    afterNextRender(() => {
      if (!this.dialog().nativeElement.open) this.dialog().nativeElement.showModal();
    }, {injector: this.injector});
  }

  protected close() {
    if (!this.busy()) this.dialog().nativeElement.close();
  }

  protected cancel(event: Event) {
    if (this.busy()) event.preventDefault();
  }

  protected clearPanel() {
    if (!this.dialog().nativeElement.open) this.panel.set(undefined);
  }

  protected setChecked(id: string, active: boolean) {
    this.checked.update(previous => {
      const next = new Set(previous);
      if (active) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected saveName() {
    const panel = this.panel();
    const name = this.name().trim();
    if (!name || (panel?.kind !== 'create' && panel?.kind !== 'rename')) return;
    const create = panel.kind === 'create';
    const request = create ? this.api.createLibrarySection(name) : this.api.renameLibrarySection(panel.section.id, name);
    this.perform(request, section => {
      this.updateSection(section);
      this.finish(create ? 'Секция создана.' : 'Секция переименована.', create ? section.id : undefined);
    });
  }

  protected addPicked() {
    const panel = this.panel();
    if (panel?.kind !== 'pick' || !this.checked().size) return;
    this.perform(this.api.addLibrarySectionEntries(panel.section.id, [...this.checked()]), section => {
      this.updateSection(section);
      this.finish('Тайтлы добавлены в секцию.');
    });
  }

  protected apply(entry: LibraryEntry) {
    this.entries.update(entries => [entry, ...(entries ?? []).filter(item => item.id !== entry.id)]);
    const moved = !!this.builtIn() && entry.status !== this.builtIn()?.status;
    const name = this.statusNav().find(section => section.status === entry.status)?.name ?? statuses[entry.status];
    this.finish(moved ? `Тайтл теперь в секции «${name}».` : 'Изменения сохранены.');
  }

  protected confirmRemoval() {
    const panel = this.panel();
    if (panel?.kind === 'delete-section') {
      this.perform(this.api.deleteLibrarySection(panel.section.id), () => {
        this.sections.update(sections => (sections ?? []).filter(section => section.id !== panel.section.id));
        this.finish('Секция удалена.', '');
      });
    } else if (panel?.kind === 'remove-entry') {
      this.perform(this.api.removeLibrarySectionEntry(panel.section.id, panel.entry.id), section => {
        this.updateSection(section);
        this.finish('Тайтл убран из секции.');
      });
    } else if (panel?.kind === 'delete-entry') {
      const id = panel.entry.id;
      this.perform(this.api.delete(id), () => {
        this.entries.update(entries => (entries ?? []).filter(entry => entry.id !== id));
        if (this.sections.hasValue()) {
          this.sections.update(sections => (sections ?? []).map(section => ({
            ...section, entryIds: section.entryIds.filter(member => member !== id),
          })));
        }
        this.finish('Тайтл удалён из библиотеки.');
      });
    }
  }

  private openMenus() {
    return Array.from(this.host.nativeElement.querySelectorAll<HTMLDetailsElement>('.library-disclosure[open]'));
  }

  private updateSection(section: LibrarySection) {
    this.sections.update(sections => (sections ?? []).some(item => item.id === section.id)
      ? (sections ?? []).map(item => item.id === section.id ? section : item)
      : [...(sections ?? []), section]);
  }

  private perform<T>(request: Observable<T>, success: (value: T) => void) {
    if (this.busy()) return;
    this.issue.set(undefined);
    this.pending.set(true);
    request.pipe(takeUntilDestroyed(this.destroy), finalize(() => this.pending.set(false))).subscribe({
      next: success,
      error: error => this.issue.set(problem(error)),
    });
  }

  private finish(message: string, section?: string) {
    this.dialog().nativeElement.close();
    this.notice.set(message);
    const restoreFocus = () => {
      const trigger = this.dialogTrigger;
      if (section === undefined && trigger?.isConnected && trigger.getClientRects().length) trigger.focus();
      else this.heading().nativeElement.focus();
    };
    if (section === undefined) {
      afterNextRender(restoreFocus, {injector: this.injector});
    } else {
      void this.router.navigate(['/library'], {queryParams: this.sectionParams(section)})
        .then(() => afterNextRender(restoreFocus, {injector: this.injector}));
    }
  }
}
