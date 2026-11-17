import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet, Routes } from '@angular/router';
import { Browse } from './browse';
import { Catalog } from './catalog';
import { SkipLink } from './common';
import { DownloadsPage } from './downloads';
import { Library } from './library';
import { TitleLocale } from './title-locale';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet/>',
})
export class App {}

@Component({
  imports: [RouterLink, RouterLinkActive, RouterOutlet, SkipLink],
  template: `
    <app-skip-link/>
    <header class="site-header">
      <div class="wrap bar">
        <a class="brand" routerLink="/catalog" aria-label="Кадр — каталог">
          <img src="kadr.svg" alt="" width="32" height="32"> <span>Кадр<span class="accent">.</span></span>
        </a>
        <nav class="pills" aria-label="Разделы">
          <a routerLink="/catalog" routerLinkActive="selected" ariaCurrentWhenActive="page">Каталог</a>
          <a routerLink="/library" routerLinkActive="selected" ariaCurrentWhenActive="page">Библиотека</a>
          <a routerLink="/browse" routerLinkActive="selected" ariaCurrentWhenActive="page">Обзор</a>
        </nav>
        <div class="title-language" role="group" aria-label="Язык названий тайтлов">
          <button type="button" lang="ru" aria-label="Русские названия" [attr.aria-pressed]="locale.language() === 'ru'"
            (click)="locale.select('ru')">RU</button>
          <button type="button" lang="en" aria-label="English titles" [attr.aria-pressed]="locale.language() === 'en'"
            (click)="locale.select('en')">EN</button>
        </div>
      </div>
    </header>
    <router-outlet/>
  `,
})
class Layout { protected readonly locale = inject(TitleLocale); }

@Component({
  imports: [RouterLink],
  template: `
    <main id="main-content" class="wrap section-page" tabindex="-1">
      <h1 class="page-heading">Страница не найдена</h1>
      <a class="button primary" routerLink="/catalog">В каталог</a>
    </main>
  `,
})
class NotFound {}

export const routes: Routes = [
  {path: 'downloads/:downloadId/read',
    loadComponent: () => import('./reader').then(module => module.Reader), title: 'Офлайн-читалка — Кадр'},
  {path: 'read/:sourceId/:externalTitleId/:chapterId/:language',
    loadComponent: () => import('./reader').then(module => module.Reader), title: 'Читалка — Кадр'},
  {
    path: '', component: Layout, children: [
      {path: '', redirectTo: 'catalog', pathMatch: 'full'},
      {path: 'catalog', component: Catalog, title: 'Каталог — Кадр'},
      {path: 'catalog/:sourceId/:externalTitleId',
        loadComponent: () => import('./title').then(module => module.TitlePage), title: 'Тайтл — Кадр'},
      {path: 'library/downloads', component: DownloadsPage, title: 'Скачанное — Кадр'},
      {path: 'library', component: Library, title: 'Библиотека — Кадр'},
      {path: 'browse', component: Browse, title: 'Обзор — Кадр'},
      {path: '**', component: NotFound, title: 'Страница не найдена — Кадр'},
    ],
  },
];
