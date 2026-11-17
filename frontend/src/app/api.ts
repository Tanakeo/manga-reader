import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map } from 'rxjs';

export interface TitleKey {
  sourceId: string;
  externalTitleId: string;
}

export interface SourceIdentity {
  sourceId: string;
  sourceName: string;
  websiteUrl: string | null;
}

export interface Source extends SourceIdentity {
  supportsTags: boolean;
  sorts: string[];
}

export interface Extension extends SourceIdentity {
  enabled: boolean;
}

export interface TitleSummary extends TitleKey {
  sourceName: string;
  title: string;
  author: string | null;
  coverUrl: string | null;
  titleRu: string | null;
  titleEn: string | null;
  publicationStatus: 'ongoing' | 'completed' | 'hiatus' | 'cancelled' | null;
  releaseYear: number | null;
}

interface License {
  code: string;
  url: string;
}

export interface TitleDetail extends TitleSummary {
  description: string | null;
  sourceUrl: string;
  license: License | null;
  tags: Tag[];
}

export interface Tag {
  id: string;
  group: string;
  names: Record<string, string>;
}

export interface CatalogQuery {
  sourceId: string;
  q: string;
  tagIds: string[];
  sort: string;
}

export interface Chapter {
  id: string;
  number: number | null;
  title: string;
  languages: string[];
  pageCount: number;
}

export interface ChapterPages {
  chapterId: string;
  title: string;
  language: string;
  pageCount: number;
  previousChapterId: string | null;
  nextChapterId: string | null;
  attribution: {author: string | null; creditsText: string; sourceUrl: string; license: License | null};
  pages: { index: number; imageUrl: string }[];
}

export interface Download {
  id: string;
  title: TitleSummary;
  chapterId: string;
  chapterTitle: string | null;
  chapterNumber: number | null;
  language: string;
  status: 'QUEUED' | 'DOWNLOADING' | 'READY' | 'FAILED' | 'DELETING';
  pagesDone: number;
  totalPages: number | null;
  error: {code: string; detail: string} | null;
}

export interface DownloadsResponse {
  supported: boolean;
  items: Download[];
}

export interface OfflineManifest {
  title: TitleSummary;
  chapter: ChapterPages;
  available: Download[];
}

export const statuses = {
  PLANNED: 'В планах',
  READING: 'Читаю',
  COMPLETED: 'Прочитано',
} as const;
export type ReadingStatus = keyof typeof statuses;
export const statusOptions = Object.entries(statuses);

export interface Progress {
  chapterId: string;
  language: string;
  pageIndex: number;
}

export interface LibraryEntry {
  id: string;
  title: TitleSummary;
  status: ReadingStatus;
  note: string | null;
  progress: Progress | null;
  createdAt: string;
  updatedAt: string;
}

export interface LibraryUpdate {
  status?: ReadingStatus;
  note?: string | null;
  progress?: Progress | null;
}

export interface Problem {
  code: string;
  detail: string;
  errors: {field: string; code: string; message: string}[];
  availableLanguages?: string[];
  invalidTagIds?: string[];
}

export function problem(error: unknown): Problem {
  if (error instanceof HttpErrorResponse && typeof error.error?.detail === 'string') {
    return error.error as Problem;
  }
  return {code: 'CONNECTION_FAILED', detail: 'Не удалось связаться с сервером. Попробуйте ещё раз.', errors: []};
}

export function fieldError(issue: Problem | undefined, field: string) {
  return issue?.errors?.find(error => error.field === field)?.message;
}

export function sameTitle(a: TitleKey, b: TitleKey) {
  return a.sourceId === b.sourceId && a.externalTitleId === b.externalTitleId;
}

export function languageName(code: string) {
  if (code === 'ru') return 'Русский';
  if (code === 'en') return 'Английский';
  return code.toUpperCase();
}

export function titleLink(key: TitleKey) {
  return ['/catalog', key.sourceId, key.externalTitleId];
}

export function readerLink(key: TitleKey, chapterId: string, language: string) {
  return ['/read', key.sourceId, key.externalTitleId, chapterId, language];
}

@Injectable({providedIn: 'root'})
export class Api {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/v1';
  private readonly libraryUrl = this.base + '/library';
  private readonly downloadsUrl = this.base + '/downloads';

  sources() {
    return this.http.get<{items: Source[]}>(this.base + '/sources').pipe(map(data => data.items));
  }

  extensions() {
    return this.http.get<{items: Extension[]}>(this.base + '/extensions').pipe(map(data => data.items));
  }

  connectSource(sourceId: string) {
    return this.http.put<Source>(this.sourceUrl(sourceId), null);
  }

  disconnectSource(sourceId: string) {
    return this.http.delete<void>(this.sourceUrl(sourceId));
  }

  catalog({q, sourceId, tagIds, sort}: CatalogQuery) {
    let params = new HttpParams();
    if (q) params = params.set('q', q);
    if (sourceId) params = params.set('sourceId', sourceId);
    if (sort) params = params.set('sort', sort);
    for (const id of tagIds) params = params.append('tagId', id);
    return this.http.get<{items: TitleSummary[]}>(this.base + '/catalog', {params}).pipe(map(data => data.items));
  }

  tags(source: string) {
    return this.http.get<{items: Tag[]}>(this.sourceUrl(source) + '/tags').pipe(map(data => data.items));
  }

  downloads(key?: TitleKey) {
    const params = key ? {sourceId: key.sourceId, externalTitleId: key.externalTitleId} : undefined;
    return this.http.get<DownloadsResponse>(this.downloadsUrl, {params});
  }

  download(key: TitleKey, chapterId: string, language: string) {
    const {sourceId, externalTitleId} = key;
    return this.http.post<Download>(this.downloadsUrl, {sourceId, externalTitleId, chapterId, language});
  }

  retryDownload(id: string) {
    return this.http.post<Download>(this.downloadUrl(id) + '/retry', null);
  }

  deleteDownload(id: string) {
    return this.http.delete<Download | null>(this.downloadUrl(id));
  }

  offline(id: string) {
    return this.http.get<OfflineManifest>(this.downloadUrl(id) + '/manifest');
  }

  private downloadUrl(id: string) {
    return this.downloadsUrl + '/' + encodeURIComponent(id);
  }

  title(key: TitleKey) {
    return this.http.get<TitleDetail>(this.titleUrl(key));
  }

  chapters(key: TitleKey) {
    return this.http.get<{items: Chapter[]}>(this.titleUrl(key) + '/chapters').pipe(map(data => data.items));
  }

  chapterCount(key: TitleKey) {
    return this.http.get<{chapterCount: number}>(this.titleUrl(key) + '/chapter-count')
      .pipe(map(data => data.chapterCount));
  }

  pages(key: TitleKey, chapterId: string, language: string) {
    return this.http.get<ChapterPages>(
      this.titleUrl(key) + '/chapters/' + encodeURIComponent(chapterId) + '/pages',
      {params: {language}},
    );
  }

  library() {
    return this.http.get<{items: LibraryEntry[]}>(this.libraryUrl).pipe(map(data => data.items));
  }

  add(key: TitleKey, status: ReadingStatus, note: string | null) {
    const {sourceId, externalTitleId} = key;
    return this.http.post<LibraryEntry>(this.libraryUrl, {sourceId, externalTitleId, status, note});
  }

  update(id: string, patch: LibraryUpdate) {
    return this.http.patch<LibraryEntry>(this.libraryUrl + '/' + encodeURIComponent(id), patch);
  }

  delete(id: string) {
    return this.http.delete<void>(this.libraryUrl + '/' + encodeURIComponent(id));
  }

  private titleUrl(key: TitleKey) {
    return this.sourceUrl(key.sourceId) + '/titles/' + encodeURIComponent(key.externalTitleId);
  }

  private sourceUrl(sourceId: string) {
    return this.base + '/sources/' + encodeURIComponent(sourceId);
  }
}
