import { inject, Injectable, signal } from '@angular/core';
import { Observable, shareReplay, tap } from 'rxjs';
import { Api, sameTitle, TitleDetail, TitleKey, TitleSummary } from '../api';

@Injectable({providedIn: 'root'})
export class TitleDetails {
  private readonly api = inject(Api);
  private readonly seed = signal<TitleSummary | undefined>(undefined);
  private readonly requests = new Map<string, {at: number; request: Observable<TitleDetail>}>();

  remember(title: TitleSummary) {
    this.seed.set(title);
  }

  summary(key: TitleKey) {
    const title = this.seed();
    return title && sameTitle(title, key) ? title : undefined;
  }

  load(key: TitleKey) {
    const id = JSON.stringify([key.workId, key.sourceId, key.externalTitleId]);
    const cached = this.requests.get(id);
    if (cached && Date.now() - cached.at < 60_000) return cached.request;
    const request = (key.workId ? this.api.work(key.workId) : this.api.title(key)).pipe(
      tap(title => this.remember(title)),
      shareReplay({bufferSize: 1, refCount: true}),
    );
    this.requests.delete(id);
    if (this.requests.size >= 12) this.requests.delete(this.requests.keys().next().value!);
    this.requests.set(id, {at: Date.now(), request});
    return request;
  }
}
