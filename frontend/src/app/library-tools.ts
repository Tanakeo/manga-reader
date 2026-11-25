import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api, BackupPreview, LibraryEntry, problem, statuses, TitleSummary, TransferPreview, titleLink } from './api';

@Component({
  imports: [RouterLink],
  template: `
    <main class="wrap section-page" id="main-content" tabindex="-1">
      <a class="back-link" routerLink="/library">← Библиотека</a>
      <h1 class="page-heading">Резервная копия</h1>
      <div class="actions">
        <button type="button" class="button primary" [disabled]="busy()" (click)="save()">Экспортировать JSON</button>
        <label>Выбрать резервную копию <input type="file" accept=".json,application/json" [disabled]="busy()" (change)="select($event)"></label>
      </div>
      <p class="muted">JSON содержит библиотеку, историю, закладки и настройки этого браузера. Файлы глав хранятся отдельно.</p>
      @if (preview(); as info) {
        <p>Записей: {{ info.entries }} · Секций: {{ info.sections }} · Глав в истории: {{ info.chapters }} · Закладок: {{ info.bookmarks }}</p>
        <p>Совпадающих записей: {{ info.conflicts }}. Существующие данные сохраняются, недостающие добавляются.</p>
        <button type="button" class="button primary" [disabled]="busy()" (click)="restore()">Восстановить</button>
      }
      @if (notice()) { <p role="status">{{ notice() }}</p> }
      @if (error()) { <p role="alert" class="error">{{ error() }}</p> }
    </main>
  `,
})
export class LibraryBackupPage {
  private readonly api = inject(Api);
  private backup?: Record<string,unknown>;
  protected readonly preview = signal<BackupPreview | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');

  protected async save() {
    await this.run(async () => {
      const data = await firstValueFrom(this.api.backupExport());
      const readerSettings: Record<string,string> = {};
      for (const key of Object.keys(localStorage).filter(key => key.startsWith('kadr-reader/'))) {
        const settings = localStorage.getItem(key);
        if (settings) readerSettings[key] = settings;
      }
      const blob = new Blob([JSON.stringify({...data,readerSettings},null,2)],{type:'application/json'});
      const href = URL.createObjectURL(blob);
      const download = document.createElement('a');
      download.href = href;
      download.download = 'kadr-library-v1.json';
      download.click();
      window.setTimeout(() => URL.revokeObjectURL(href),1000);
    });
  }

  protected async select(event:Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    this.backup = undefined;
    this.preview.set(null);
    if (!file) return;
    await this.run(async () => {
      if (file.size > 8_000_000) throw new Error('Файл больше 8 МБ.');
      const backup = JSON.parse(await file.text()) as Record<string,unknown>;
      this.preview.set(await firstValueFrom(this.api.backupPreview(backup)));
      this.backup = backup;
    });
  }

  protected async restore() {
    const backup = this.backup;
    if (!backup) return;
    await this.run(async () => {
      await firstValueFrom(this.api.backupRestore(backup));
      if (backup['readerSettings'] && typeof backup['readerSettings'] === 'object') {
        for (const [key,value] of Object.entries(backup['readerSettings'])) {
          if (key.startsWith('kadr-reader/') && key.length <= 280 && typeof value === 'string'
              && value.length <= 500 && localStorage.getItem(key) === null) localStorage.setItem(key,value);
        }
      }
      this.notice.set('Данные восстановлены.');
      this.preview.set(null);
    });
  }

  private async run(work: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try { await work(); }
    catch (error) { this.error.set(error instanceof Error ? error.message : problem(error).detail); }
    finally { this.busy.set(false); }
  }
}

@Component({
  imports: [RouterLink,FormsModule],
  template: `
    <main class="wrap section-page" id="main-content" tabindex="-1">
      <a class="back-link" routerLink="/library">← Библиотека</a>
      <h1 class="page-heading">Перенос между источниками</h1>
      @if (origin(); as row) {
        <p>Исходный тайтл: {{ row.title.title }} · {{ row.title.sourceName }}</p>
        <form class="actions" (ngSubmit)="search()">
          <label>Название тайтла <input name="query" [(ngModel)]="query" maxlength="120" required></label>
          <button class="button secondary" [disabled]="busy() || !query.trim()">Найти в каталоге</button>
        </form>
      }
      <ul class="chapter-list">
        @for (candidate of candidates(); track candidate.sourceId + '/' + candidate.externalTitleId) {
          <li><button class="button secondary" [disabled]="busy()" (click)="choose(candidate)">{{ candidate.title }} · {{ candidate.sourceName }}</button></li>
        }
      </ul>
      @if (preview(); as result) {
        <section class="surface" style="padding:16px">
          <h2>{{ selected()?.title }}</h2>
          <p>{{ statusName(result.status) }} @if(result.note) { · {{ result.note }} }</p>
          <p>{{ result.progress ? 'Найдена соответствующая глава. Откроется с первой страницы.' : 'Соответствие главы не найдено.' }}</p>
          @if (result.targetInLibrary) { <p>Тайтл назначения уже в библиотеке.</p> }
          @else {
            <p>Статус, заметка и подборки будут скопированы. Исходная запись останется в библиотеке.</p>
            <button class="button primary" [disabled]="busy()" (click)="confirm()">Подтвердить копирование</button>
          }
        </section>
      }
      @if (copied(); as target) { <p role="status">Запись добавлена. <a [routerLink]="title(target)">Открыть тайтл</a></p> }
      @if (notice()) { <p role="status">{{ notice() }}</p> }
      @if (error()) { <p role="alert" class="error">{{ error() }}</p> }
    </main>
  `,
})
export class LibraryTransferPage {
  readonly entryId = input.required<string>();
  private readonly api = inject(Api);
  protected readonly origin = signal<LibraryEntry | null>(null);
  protected readonly candidates = signal<TitleSummary[]>([]);
  protected readonly selected = signal<TitleSummary | null>(null);
  protected readonly preview = signal<TransferPreview | null>(null);
  protected readonly copied = signal<TitleSummary | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly title = titleLink;
  protected query = '';

  protected statusName(status:keyof typeof statuses) { return statuses[status]; }

  ngOnInit() {
    void this.run(async () => {
      const entry = (await firstValueFrom(this.api.library())).find(row => row.id === this.entryId());
      if (!entry) { this.error.set('Запись не найдена.'); return; }
      this.origin.set(entry);
      this.query = entry.title.title.slice(0,120);
    });
  }

  protected async search() {
    await this.run(async () => {
      this.preview.set(null);
      this.candidates.set([]);
      const data = await firstValueFrom(this.api.catalog({sourceId:'',q:this.query,tagIds:[],sort:''}));
      const items = data.items.filter(item => item.sourceId !== this.origin()?.title.sourceId);
      this.candidates.set(items);
      if (!items.length) this.notice.set('В первой порции каталога совпадений нет. Попробуйте другое название.');
    });
  }

  protected async choose(candidate:TitleSummary) {
    await this.run(async () => {
      this.preview.set(null);
      this.selected.set(candidate);
      this.preview.set(await firstValueFrom(this.api.transferPreview(this.entryId(),candidate)));
    });
  }

  protected async confirm() {
    const destination = this.selected();
    if (!destination || !this.preview()) return;
    await this.run(async () => {
      await firstValueFrom(this.api.transfer(this.entryId(),destination));
      this.copied.set(destination);
      this.preview.set(null);
    });
  }

  private async run(work:() => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try { await work(); }
    catch (error) { this.error.set(problem(error).detail); }
    finally { this.busy.set(false); }
  }
}
