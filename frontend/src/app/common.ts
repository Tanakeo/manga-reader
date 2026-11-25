import { booleanAttribute, Component, computed, DOCUMENT, effect, inject, input, linkedSignal, output, signal } from '@angular/core';
import { LucideBookOpen as BookOpen, LucideImageOff as ImageOff, LucideDynamicIcon } from '@lucide/angular';
import type { TitleKey } from './api';

// Confirmed matches for the same series in MangaDex, keyed by MangaKakalot slug.
const kakalotCovers = new Map([
  ['martial-peak', 'b1461071-bfbb-43e7-a5b6-a7ba5904649f/covers/d175e74d-4ab7-47f4-8196-371aa51d8c2d.png'],
  ['tales-of-demons-and-gods', '812f9696-048f-496d-9ed4-84c99345d1fb/covers/4dcdd628-9195-4fa8-8079-50541ed60515.jpg'],
  ['solo-leveling', '32d76d19-8a05-4db0-9fc2-e0b0648fe9d0/covers/e90bdc47-c8b9-4df7-b2c0-17641b645ee1.jpg'],
  ['versatile-mage', '44f84cc1-819c-47a6-a59f-43d016f72b43/covers/73312f00-92d7-4d4c-b6a5-79e1b64c113e.jpg'],
  ['apotheosis', 'cdae359d-5fa9-4d4e-b11a-69dd32b09742/covers/601a10fc-61aa-4102-9387-f6bf613fb934.jpg'],
  ['the-beginning-after-the-end', '4ada20eb-085a-491a-8c49-477ab42014d7/covers/4298e756-edf0-4bd6-9b83-340bfdb27771.jpg'],
  ['one-piece', 'a1c7c817-4e59-43b7-9365-09675a149a6f/covers/2f4aca53-64c7-46ac-ae85-3bc9b3169890.png'],
  ['kimetsu-no-yaiba', '789642f8-ca89-4e4e-8f7b-eee4d17ea08b/covers/60530e72-f76f-45d5-b6f9-f95e05058fc3.png'],
  ['star-martial-god-technique', '267db3f7-fd9c-4395-ac36-9ffacd772473/covers/92888290-1aa6-41b5-bb37-31dbd20ba725.jpg'],
  ['rebirth-of-the-urban-immortal-cultivator', '31d67518-dd05-46d2-9d59-4ba730a23a10/covers/1f139562-1ad2-4004-8637-e52064f6204f.jpg'],
  ['the-devil-butler', '69e218ec-93eb-4025-ae39-33d47b1160e0/covers/b60fd7d0-d3b0-492b-8542-f22d2a9f2205.jpg'],
  ['komi-san-wa-komyushou-desu', 'a96676e5-8ae2-425e-b549-7f15dd34a6d8/covers/f8f44329-1dd7-4301-9ec7-a4a76182e8eb.jpg'],
  ['tomo-chan-wa-onnanoko', '76ee7069-23b4-493c-bc44-34ccbf3051a8/covers/cfac7a67-3c4b-4b29-8599-bad47613185c.jpg'],
  ['a-returner-s-magic-should-be-special', '6a468761-5bd6-4de0-a0cb-47cb456ac2e0/covers/98162533-1838-4639-8419-2f19f8af651b.png'],
  ['kingdom', '077a3fed-1634-424f-be7a-9a96b7f07b78/covers/8bc7a592-764d-4a89-a0ae-741347b894ce.jpg'],
  ['jujutsu-kaisen', 'c52b2ce3-7f95-469c-96b0-479524fb7a1a/covers/6d9134b2-21ea-4d02-ac2b-7c0d1c6a2aaa.jpg'],
  ['mairimashita-iruma-kun', 'd7037b2a-874a-4360-8a7b-07f2899152fd/covers/59ab55ed-a537-426e-b755-6337740df580.jpg'],
  ['my-wife-is-a-demon-queen', '16800ea3-d7cb-4a03-801e-bfbb4dc12bea/covers/eabf8a7a-fe9b-4803-8bb3-3db608862aa3.png'],
  ['onepunch-man', 'd8a959f7-648e-4c8d-8f23-f1f3f8e129f3/covers/d22cfde3-268a-48a3-b5ed-73772834764d.jpg'],
  ['release-that-witch', 'aafb046c-9de4-4c3d-990c-6d75b079c0b9/covers/ee278e22-9bfb-4b1b-9681-f8782b93e57b.jpg'],
  ['tokyo-manji-revengers', '59b36734-f2d6-46d7-97c0-06cfd2380852/covers/3676571a-9d2b-48e6-8639-d4591725759a.jpg'],
  ['mousou-telepathy', '3d25d7da-893f-400e-9aeb-6163773c671a/covers/0e6104af-8499-40cc-a8c3-6ebef89ccf94.jpg'],
  ['boku-no-hero-academia', '4f3bcae4-2d96-4c9d-932c-90181d9c873e/covers/c7a7101a-8e22-442b-a1db-55ba9ef5b1ab.jpg'],
  ['mercenary-enrollment', '9ecd208c-c592-4462-9f57-4d7d4a9f956f/covers/75e7fac6-c9f4-43ce-ae0e-cbf06f28f99b.jpg'],
]);

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
    @if (coverUrl(); as url) {
      <img [src]="url" [alt]="label()" (error)="markFailed(url)"
        [attr.title]="url === alternateMangaDexCover() ? 'Обложка из MangaDex' : null"
        [attr.loading]="eager() ? 'eager' : 'lazy'" decoding="async">
    } @else if (showFallback()) {
      <div class="cover-fallback" role="img" [attr.aria-label]="label() + ' — обложка недоступна'">
        <svg class="icon" [lucideIcon]="imageOff" [size]="26" aria-hidden="true"/>
        <span>Обложка недоступна</span>
      </div>
    }
  `,
})
export class Cover {
  readonly src = input.required<string | null>();
  readonly titleKey = input<TitleKey>();
  readonly label = input.required<string>();
  readonly eager = input(false);
  readonly showFallback = input(true);
  protected readonly imageOff = ImageOff;
  protected readonly alternateMangaDexCover = computed(() => {
    const key = this.titleKey();
    const path = key?.sourceId === 'mangakakalot' ? kakalotCovers.get(key.externalTitleId) : undefined;
    return path ? '/api/v1/sources/mangadex/titles/' + path : undefined;
  });
  private readonly candidates = computed(() => {
    const src = this.src()?.trim();
    const alternate = this.alternateMangaDexCover();
    const key = this.titleKey();
    if (key?.sourceId === 'mangakakalot') {
      const cover = '/api/v1/sources/mangakakalot/titles/' + encodeURIComponent(key.externalTitleId) + '/cover';
      return alternate ? [cover, alternate] : [cover];
    }
    if (!src) return [];
    // Library entries may still contain MangaDex CDN URLs saved before covers used the backend.
    const legacy = src.match(/^https:\/\/uploads\.mangadex\.org\/covers\/([a-f0-9-]{36})\/([a-z0-9][a-z0-9._-]{0,249})\.512\.jpg$/i);
    if (legacy) return [`/api/v1/sources/mangadex/titles/${legacy[1]}/covers/${legacy[2]}`];

    // MangaLib's public catalogue lists thumbnails; the same cover without the suffix is the detail image.
    const thumbnail = src.match(/^(https:\/\/covers\.mixlib\.me\/uploads\/cover\/[a-z0-9._~-]+\/cover\/[a-z0-9-]+)_thumb(\.(?:jpe?g|png|webp)(?:\?[^#]*)?)$/i);
    if (thumbnail) return [thumbnail[1] + thumbnail[2], src];
    return [src];
  });
  private readonly failed = linkedSignal({source: this.candidates, computation: () => new Set<string>()});
  protected readonly coverUrl = computed(() => this.candidates().find(url => !this.failed().has(url)));
  readonly missing = computed(() => !this.coverUrl());

  protected markFailed(url: string) {
    this.failed.update(previous => new Set(previous).add(url));
  }
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
