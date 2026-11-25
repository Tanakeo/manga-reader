import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api, LocalImportResult, problem, readerLink, titleLink } from './api';

@Component({
  imports: [FormsModule,RouterLink],
  template: `
    <main class="wrap section-page" id="main-content" tabindex="-1">
      <a class="back-link" routerLink="/browse">← Обзор</a>
      <h1 class="page-heading">Импорт комикса</h1>
      <form class="surface" style="padding:16px;display:grid;gap:12px" (ngSubmit)="submit()">
        <label>Тайтл <input name="title" [(ngModel)]="title" maxlength="200" required></label>
        <label>Глава <input name="chapter" [(ngModel)]="chapter" maxlength="200" required></label>
        <label>Язык <select name="language" [(ngModel)]="language"><option value="ru">Русский</option><option value="en">Английский</option></select></label>
        <label>Источник файлов <select name="kind" [(ngModel)]="kind" (ngModelChange)="files=[]">
          <option value="files">Изображения или CBZ / ZIP</option><option value="folder">Папка на устройстве</option>
          <option value="server">Папка на сервере</option>
        </select></label>
        @if (kind === 'files') {
          <label>Файлы главы <input type="file" multiple accept=".cbz,.zip,.jpeg,.jpg,.png,.gif" (change)="choose($event)"></label>
        } @else if (kind === 'folder') {
          <label>Папка главы <input type="file" webkitdirectory multiple (change)="choose($event)"></label>
        } @else {
          <label>Путь внутри папки импорта <input name="path" [(ngModel)]="path" required></label>
        }
        <button class="button primary" [disabled]="busy() || !title.trim() || !chapter.trim() || (kind === 'server' ? !path.trim() : !files.length)">
          {{ busy() ? 'Импортируем…' : 'Импортировать' }}
        </button>
      </form>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (result(); as item) {
        <p role="status">{{ item.duplicate ? 'Глава уже импортирована.' : 'Импорт завершён.' }} Страниц: {{ item.pageCount }}.</p>
        <div class="actions">
          <a class="button primary" [routerLink]="reader(item,item.chapterId,item.language)">Читать</a>
          <a class="button secondary" [routerLink]="openTitle(item)">Открыть тайтл</a>
        </div>
      }
    </main>
  `,
})
export class LocalImport {
  private readonly api = inject(Api);
  protected title = '';
  protected chapter = '';
  protected language = 'ru';
  protected kind = 'files';
  protected path = '';
  protected files: File[] = [];
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly result = signal<LocalImportResult | null>(null);
  protected readonly reader = readerLink;
  protected readonly openTitle = titleLink;

  protected choose(event: Event) {
    this.files = Array.from((event.target as HTMLInputElement).files ?? [])
      .filter(file => /\.(cbz|zip|jpe?g|png|gif)$/i.test(file.name));
  }

  protected async submit() {
    if (this.busy()) return;
    this.busy.set(true);
    this.result.set(null);
    this.error.set('');
    const fields = {title:this.title,chapter:this.chapter,language:this.language};
    try {
      if (this.kind === 'server') {
        this.result.set(await firstValueFrom(this.api.importServerFolder({...fields,path:this.path})));
      } else {
        const body = new FormData();
        for (const [key,value] of Object.entries(fields)) body.set(key,value);
        for (const file of this.files) body.append('files',file,file.webkitRelativePath || file.name);
        this.result.set(await firstValueFrom(this.api.importLocal(body)));
      }
    } catch (error) { this.error.set(problem(error).detail); }
    finally { this.busy.set(false); }
  }
}
