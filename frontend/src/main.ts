import { provideHttpClient } from '@angular/common/http';
import { isDevMode } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { App, routes } from './app/app';
import { CatalogTranslationLoader } from './app/title-locale';

bootstrapApplication(App, {
  providers: [
    provideHttpClient(),
    provideTransloco({
      config: {availableLangs: ['ru', 'en'], defaultLang: 'ru', reRenderOnLangChange: true, prodMode: !isDevMode()},
      loader: CatalogTranslationLoader,
    }),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({
      scrollPositionRestoration: 'enabled',
      anchorScrolling: 'enabled',
    })),
  ],
}).catch(error => console.error(error));
