import { HttpClient } from '@angular/common/http';
import { DOCUMENT, inject, Injectable, Pipe, PipeTransform } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Translation, TranslocoLoader, TranslocoService } from '@jsverse/transloco';
import { TitleSummary } from './api';

type TitleLanguage = 'ru' | 'en';
const STORAGE_KEY = 'kadr.titleLanguage';

@Injectable({providedIn: 'root'})
export class CatalogTranslationLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(language: string) {
    return this.http.get<Translation>(`i18n/${language}.json`);
  }
}

@Injectable({providedIn: 'root'})
export class TitleLocale {
  private readonly transloco = inject(TranslocoService);
  private readonly document = inject(DOCUMENT);
  readonly language = toSignal(this.transloco.langChanges$, {requireSync: true});

  constructor() {
    try {
      if (this.document.defaultView?.localStorage.getItem(STORAGE_KEY) === 'en') {
        this.transloco.setActiveLang('en');
      }
    } catch {
      // The language can still be changed when storage is unavailable.
    }
  }

  select(language: TitleLanguage) {
    this.transloco.setActiveLang(language);
    try {
      this.document.defaultView?.localStorage.setItem(STORAGE_KEY, language);
    } catch {
      // Leave the current language active for this session.
    }
  }
}

@Pipe({name: 'localizedTitle'})
export class LocalizedTitle implements PipeTransform {
  transform(title: TitleSummary | undefined, language: string): string {
    if (!title) return '';
    return language === 'en' ? title.titleEn || title.title || title.titleRu || ''
      : title.titleRu || title.title || title.titleEn || '';
  }
}
