import { booleanAttribute, Component, computed, DOCUMENT, effect, inject, input, output, signal } from '@angular/core';
import { LucideBookOpen as BookOpen, LucideImageOff as ImageOff, LucideDynamicIcon } from '@lucide/angular';

const loadingMessages = [
  {text: 'Cooking', lang: 'en'},
  {text: 'Shmooking', lang: 'en'},
  {text: 'Листаем', lang: 'ru'},
  {text: 'Шуршим страницами', lang: 'ru'},
  {text: 'Сдуваем пыль с полки', lang: 'ru'},
];

const loadingCharacters = [
  {animation: 'loading/character-dance.png', still: 'loading/character-still.png'},
  {animation: 'loading/chibi-wave.webp', still: 'loading/chibi-wave-still.webp'},
  {animation: 'loading/chibi-sprint.webp', still: 'loading/chibi-sprint-still.webp'},
  {animation: 'loading/michona-jump.png', still: 'loading/michona-still.png'},
];

@Component({
  selector: 'app-cover',
  imports: [LucideDynamicIcon],
  host: {'[class.missing]': 'missing()'},
  template: `
    @if (missing()) {
      <div class="cover-fallback" role="img" [attr.aria-label]="label() + ' — обложка недоступна'">
        <svg class="icon" [lucideIcon]="imageOff" [size]="26" aria-hidden="true"/>
        <span>Обложка недоступна</span>
      </div>
    } @else {
      <img [src]="coverUrl()" [alt]="label()" (error)="failed.set(coverUrl())"
        [attr.loading]="eager() ? 'eager' : 'lazy'" decoding="async">
    }
  `,
})
export class Cover {
  readonly src = input.required<string | null>();
  readonly label = input.required<string>();
  readonly eager = input(false);
  protected readonly imageOff = ImageOff;
  protected readonly failed = signal<string | null>(null);
  protected readonly coverUrl = computed(() => {
    const src = this.src();
    // Library entries may still contain MangaDex CDN URLs saved before covers used the backend.
    const legacy = src?.match(/^https:\/\/uploads\.mangadex\.org\/covers\/([a-f0-9-]{36})\/([a-z0-9][a-z0-9._-]{0,249})\.512\.jpg$/i);
    return legacy ? `/api/v1/sources/mangadex/titles/${legacy[1]}/covers/${legacy[2]}` : src;
  });
  protected readonly missing = computed(() => !this.coverUrl() || this.failed() === this.coverUrl());
}

@Component({
  selector: 'app-state',
  imports: [LucideDynamicIcon],
  template: `
    @if (kind() === 'loading') {
      <div class="loading-state" [class.loading-state-featured]="artwork()">
        <span class="visually-hidden" role="status">{{ message() }}</span>
        @if (visible()) {
          <div class="loading-visual" aria-hidden="true">
            @if (artwork()) {
              <div class="loading-character">
                @if (imageFailed()) {
                  <svg class="icon" [lucideIcon]="book" [size]="44"/>
                } @else {
                  <picture>
                    <source media="(prefers-reduced-motion: reduce)" [srcset]="character.still">
                    <img [src]="character.animation" width="200" height="200" alt=""
                      (error)="imageFailed.set(true)">
                  </picture>
                }
              </div>
            } @else {
              <span class="spinner"></span>
            }
            <p class="loading-phrase" [attr.lang]="phrase.lang">{{ phrase.text }}<span>…</span></p>
          </div>
        }
      </div>
    } @else {
      <div class="state" [attr.role]="kind() === 'error' ? 'alert' : 'status'">
        @if (kind() === 'empty') { <svg class="icon" [lucideIcon]="book" [size]="26" aria-hidden="true"/> }
        <p>{{ message() }}</p>
        @if (kind() === 'error') {
          <button class="button secondary" type="button" (click)="retry.emit()">Повторить</button>
        }
        <ng-content/>
      </div>
    }
  `,
})
export class State {
  readonly kind = input.required<'loading' | 'empty' | 'error'>();
  readonly message = input.required<string>();
  readonly artwork = input(false, {transform: booleanAttribute});
  readonly retry = output<void>();
  protected readonly book = BookOpen;
  protected readonly imageFailed = signal(false);
  protected readonly phrase = loadingMessages[Math.floor(Math.random() * loadingMessages.length)];
  protected readonly character = loadingCharacters[Math.floor(Math.random() * loadingCharacters.length)];
  protected readonly visible = signal(false);

  constructor() {
    effect(onCleanup => {
      this.visible.set(false);
      if (this.kind() !== 'loading') return;
      const timer = setTimeout(() => this.visible.set(true), 220);
      onCleanup(() => clearTimeout(timer));
    });
  }
}

@Component({
  selector: 'app-skip-link',
  template: '<a class="skip" href="#main-content" (click)="skip($event)">{{ label() }}</a>',
})
export class SkipLink {
  private readonly document = inject(DOCUMENT);
  readonly label = input('К содержанию');

  protected skip(event: MouseEvent) {
    const main = this.document.getElementById('main-content');
    if (!main) return;
    event.preventDefault();
    main.focus({preventScroll: true});
    main.scrollIntoView({block: 'start'});
  }
}
