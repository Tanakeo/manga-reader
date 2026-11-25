import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { catchError, defer, EMPTY, expand, from, map, mergeMap, Observable, retry, Subject, switchMap, takeUntil, tap, throwError, timer } from 'rxjs';

export interface TitleKey {
  sourceId: string;
  externalTitleId: string;
  workId?: string;
}

export interface LibraryUpdateItem extends TitleKey {
  id: number; title: string; titleRu: string | null; titleEn: string | null;
  chapterId: string; chapterTitle: string; language: string; isRead: boolean;
  publishedAt: string | null; discoveredAt: string;
}
export interface LocalImportResult extends TitleKey {
  chapterId: string; language: string; pageCount: number; duplicate: boolean;
}
export interface ChapterBookmark {chapterId:string;language:string;pageIndex:number|null}
export type ChapterAction = 'bookmark' | 'unbookmark' | 'read' | 'unread' | 'download';
export interface ChapterActionResult {item:ChapterBookmark;success:boolean;code:string|null;detail:string|null}
export interface BackupPreview {entries:number;sections:number;chapters:number;bookmarks:number;conflicts:number}
export interface TransferPreview {status:ReadingStatus;note:string|null;progress:Progress|null;targetInLibrary:boolean}

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
  description?: string | null;
  version?: number;
  selectedTitleId?: string;
  coverTitle?: TitleKey | null;
  sources?: WorkSource[];
  metrics?: WorkMetric[];
  sourceName: string;
  title: string;
  author: string | null;
  coverUrl: string | null;
  titleRu: string | null;
  titleEn: string | null;
  publicationStatus: 'ongoing' | 'completed' | 'hiatus' | 'cancelled' | null;
  releaseYear: number | null;
  chapterAvailability?: ChapterAvailability;
  latestChapterAt?: string | null;
}

export interface WorkSource extends TitleSummary {
  titleId: string;
  connected: boolean;
  supportedLanguages: string[];
}
export interface WorkMetric {
  provider: string;
  providerExternalId: string;
  metric: 'FOLLOWERS' | 'RATING_BAYESIAN' | 'RATING_MEAN';
  value: number | null;
  scaleMax: number | null;
  sampleSize: number | null;
  fetchedAt: string;
  stale: boolean;
  state: 'AVAILABLE' | 'STALE' | 'MISSING' | 'ERROR';
}
export interface WorkDetail extends TitleDetail {
  workId: string;
  allSources: WorkSource[];
  preference: {titleId: string; language: string | null; version: number} | null;
  libraryEntries: {id: string; title_id: string; status: ReadingStatus; note: string | null}[];
  relatedWorks?: string[];
}
export interface Collection extends CatalogResponse {
  id: string;
  title: string;
  basis: string;
  tagId?: string;
  state: 'READY' | 'EMPTY' | 'STALE' | 'UNAVAILABLE';
}
export interface ShowcaseResponse {
  generatedAt: string;
  collections: Collection[];
  partial: boolean;
  providerStates: {provider: string; availableMetrics: number; queuedTitles: number; lastSuccessAt: string | null;
    stale: boolean; error: SourceError | null}[];
}

interface License {
  code: string;
  url: string;
}

export interface TitleDetail extends TitleSummary {
  preference?: {titleId:string;language:string|null;version:number} | null;
  metadataError?: {code: string; detail: string; stage: string};
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
  chapterLanguages?: string[];
}

export interface ChapterAvailability {
  state: 'UNVERIFIED' | 'CHECKING' | 'PARTIAL' | 'COMPLETE' | 'ERROR';
  presence: 'UNKNOWN' | 'FOUND' | 'NONE';
  languages: string[];
  chapterCount: number | null;
  editionCount: number | null;
  knownChapterCount: number;
  checkedAt?: string | null;
  lastSuccessAt?: string | null;
  nextCheckAt?: string | null;
  error: SourceError | null;
}
export interface SourceError {
  code: string; detail: string; stage?: string; retryable?: boolean; retryAfterSeconds?: number | null;
}
export interface CatalogResponse {
  items: TitleSummary[];
  page: {total: number; hasMore: boolean; nextCursor: string | null; snapshotId: string;
    limit?: number; capturedAt?: string; expiresAt?: string};
  partial: boolean;
  sourceStates: {sourceId: string; metadataState: string; collectedTitleCount: number;
    verifiedTitleCount: number; hiddenTitleCount: number; availableTitleCount?: number;
    sourceTotal?: number | null; stopReason?: string | null; error: SourceError | null}[];
}
interface ChapterResponse {
  items: Chapter[];
  availability: ChapterAvailability;
  page: {hasMore: boolean; nextCursor: string | null};
}
export interface SyncRun {
  runId: string; state: string; phase: string; uniqueTitlesSeen: number; chaptersCheckedTitles: number;
  pagesCommitted: number; sourceTotal?: number | null; nextRetryAt?: string | null;
  stopReason: string | null; error: SourceError | null;
}
export interface SyncStatus {
  current: SyncRun | null;
  lastFinished: SyncRun | null;
  coverage: CatalogResponse['sourceStates'][number];
}

export interface Chapter {
  id: string;
  number: number | null;
  title: string;
  languages: string[];
  pageCount: number | null;
  logicalChapterId?: string;
  translationGroup?: string | null;
}

export interface ChapterPages {
  manifestVersion?: string;
  fetchedAt?: string;
  expiresAt?: string | null;
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
  status: 'QUEUED' | 'DOWNLOADING' | 'PAUSED' | 'READY' | 'FAILED' | 'DELETING';
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

export interface ReadingResume extends Progress { downloadId: string | null; }
export interface ChapterReadState {
  chapterId: string;
  language: string;
  pageIndex: number | null;
  pageCount: number | null;
  maxSeenPageIndex: number | null;
  seenPageIndexes: number[];
  isRead: boolean;
  completedAt: string | null;
  lastReadAt: string | null;
  activeSeconds: number;
  origin: 'ONLINE' | 'OFFLINE' | 'LEGACY' | null;
}
export interface ReadingState {
  revision: string;
  resume: ReadingResume | null;
  chapters: ChapterReadState[];
}
export interface ReadingSession { sessionId: string; revision: string; resume: ReadingResume | null; }
export interface ReadingSample extends Progress {
  downloadId: string | null;
  manifestVersion: string | null;
  seenPageIndexes: number[];
  reachedEnd: boolean;
  activeSecondsDelta: number;
}
export interface ReadingEvent extends ReadingSample {
  eventId: string;
  sessionId: string;
  sequence: number;
  expectedRevision: string;
}
export interface ReadingSaved {
  applied: boolean;
  revision: string;
  resume: ReadingResume | null;
  chapter: ChapterReadState | null;
}
export interface ReadingHistoryItem extends ReadingResume {
  title: TitleSummary;
  chapterTitle: string;
  isRead: boolean;
  lastReadAt: string;
  origin: ChapterReadState['origin'];
}
export interface ReadingHistory {
  items: ReadingHistoryItem[];
  page: {hasMore: boolean; nextCursor: string | null};
}

export interface LibraryEntry {
  id: string;
  title: TitleSummary;
  status: ReadingStatus;
  note: string | null;
  progress: Progress | null;
  readingRevision: string;
  createdAt: string;
  updatedAt: string;
}

export interface LibrarySection {
  id: string;
  name: string;
  entryIds: string[];
  systemKey: 'planned' | 'reading' | 'completed' | 'downloads' | null;
}

export function libraryStatusParams(status: ReadingStatus) {
  return {section: status.toLowerCase()};
}

export interface LibraryUpdate {
  status?: ReadingStatus;
  note?: string | null;
  progress?: Progress | null;
  expectedReadingRevision?: string;
}

export interface Problem {
  code: string;
  detail: string;
  errors: {field: string; code: string; message: string}[];
  availableLanguages?: string[];
  invalidTagIds?: string[];
  availability?: ChapterAvailability;
  stage?: string;
  sourceId?: string;
  retryable?: boolean;
  retryAfterSeconds?: number | null;
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
  return key.workId ? ['/catalog','works',key.workId] : ['/catalog', key.sourceId, key.externalTitleId];
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
  private readonly chapterChecks = signal(new Map<string, ChapterAvailability>());
  private readonly imageQueue = new Subject<{url: string; result: Subject<Blob>; cancel: Subject<void>; cancelled: boolean}>();

  constructor() {
    this.imageQueue.pipe(mergeMap(job => job.cancelled ? EMPTY : this.fetchImage(job.url).pipe(
      takeUntil(job.cancel),
      tap({next: blob => { job.result.next(blob); job.result.complete(); },
        error: error => job.result.error(error)}),
      catchError(() => EMPTY),
    ), 2)).subscribe();
  }

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

  catalog({q, sourceId, tagIds, sort, chapterLanguages}: CatalogQuery, cursor?: string) {
    let params = new HttpParams().set('view', 'works');
    if (q) params = params.set('q', q);
    if (sourceId) params = params.set('sourceId', sourceId);
    if (sort) params = params.set('sort', sort);
    for (const id of tagIds) params = params.append('tagId', id);
    for (const language of chapterLanguages ?? []) params = params.append('chapterLanguage', language);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<CatalogResponse>(this.base + '/catalog', {params: params.set('limit', 24)});
  }

  work(id: string, language = '') {
    return this.http.get<WorkDetail>(this.base + '/works/' + encodeURIComponent(id),
      {params: language ? {chapterLanguage: language} : {}});
  }

  preferSource(id: string, titleId: string, language: string | null, expectedVersion: number) {
    return this.http.put<WorkDetail>(this.base + '/works/' + encodeURIComponent(id) + '/source-preference',
      {titleId, language, expectedVersion});
  }

  showcase(language = '') {
    return this.http.get<ShowcaseResponse>(this.base + '/showcase', {params: language ? {chapterLanguage: language} : {}});
  }

  collection(id: string, language = '', tagId = '', cursor = '') {
    return this.http.get<Collection>(this.base + '/showcase/' + encodeURIComponent(id),
      {params: {...(language ? {chapterLanguage: language} : {}), ...(tagId ? {tagId} : {}), ...(cursor ? {cursor} : {})}});
  }

  syncStatus(source: string) {
    return this.http.get<SyncStatus>(this.sourceUrl(source) + '/catalog-sync');
  }
  startSync(source: string) {
    return this.http.post<SyncRun>(this.sourceUrl(source) + '/catalog-sync', {mode: 'FULL'});
  }
  changeSync(source: string, run: string, action: 'pause' | 'resume') {
    return this.http.post<SyncRun>(this.sourceUrl(source) + '/catalog-sync/' + run + '/' + action, null);
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

  checkChapters(key: TitleKey) {
    return this.http.post<{availability: ChapterAvailability}>(this.titleUrl(key) + '/chapter-checks', null);
  }

  chapters(key: TitleKey, language = '') {
    return defer(() => {
    let polls = 0;
    let items: Chapter[] = [];
    const seen = new Set<string>();
    const request = (cursor?: string | null) => this.http.get<ChapterResponse>(this.titleUrl(key) + '/chapters',
      {params: {...(cursor ? {cursor} : {}), ...(language ? {language} : {})}}).pipe(
      tap(data => this.chapterChecks.update(checks => new Map(checks).set(this.titleUrl(key) + '|' + language, data.availability))),
      catchError(error => {
        if (error.error?.availability) this.chapterChecks.update(items =>
          new Map(items).set(this.titleUrl(key) + '|' + language, error.error.availability));
        return throwError(() => error);
      }),
    );
    const pending = (data: ChapterResponse) => ['UNVERIFIED', 'CHECKING'].includes(data.availability.state);
    return request().pipe(
      expand(data => {
        if (data.page.hasMore && data.page.nextCursor && !seen.has(data.page.nextCursor)) {
          seen.add(data.page.nextCursor);
          return request(data.page.nextCursor);
        }
        if (pending(data) && polls++ < 20) {
          return timer(2000).pipe(switchMap(() => { items = []; seen.clear(); return request(); }));
        }
        return EMPTY;
      }),
      map(data => {
        const unique = new Map(items.map(item => [item.id, item]));
        for (const item of data.items) unique.set(item.id, item);
        items = [...unique.values()];
        return items;
      }),
    );
    }).pipe(retry({count: 1, delay: error => error.error?.code === 'CHAPTER_LIST_CHANGED'
      ? timer(100) : throwError(() => error)}));
  }

  chapterAvailability(key: TitleKey, language = '') { return this.chapterChecks().get(this.titleUrl(key) + '|' + language); }

  image(url: string) {
    return new Observable<Blob>(subscriber => {
      const result = new Subject<Blob>();
      const job = {url, result, cancel: new Subject<void>(), cancelled: false};
      const subscription = result.subscribe(subscriber);
      this.imageQueue.next(job);
      return () => { job.cancelled = true; job.cancel.next(); job.cancel.complete(); subscription.unsubscribe(); };
    });
  }

  private fetchImage(url: string) {
    return this.http.get(url, {responseType: 'blob'}).pipe(catchError((error: HttpErrorResponse) => {
      if (!(error.error instanceof Blob)) return throwError(() => error);
      return from(error.error.text()).pipe(mergeMap(text => {
        let issue: unknown;
        try { issue = JSON.parse(text); } catch { issue = {detail: 'Изображение не загрузилось.'}; }
        return throwError(() => new HttpErrorResponse({error: issue, status: error.status, url: error.url ?? undefined}));
      }));
    }));
  }

  chapterCount(key: TitleKey) {
    return this.http.get<{chapterCount: number | null; availability: ChapterAvailability}>(this.titleUrl(key) + '/chapter-count')
      .pipe(map(data => data.chapterCount));
  }

  pages(key: TitleKey, chapterId: string, language: string, refresh = false) {
    return this.http.get<ChapterPages>(
      this.titleUrl(key) + '/chapters/' + encodeURIComponent(chapterId) + '/pages',
      {params: {language, ...(refresh ? {refresh: 'true'} : {})}},
    );
  }

  library() {
    return this.http.get<{items: LibraryEntry[]}>(this.libraryUrl).pipe(map(data => data.items));
  }

  librarySections() {
    return this.http.get<{items: LibrarySection[]}>(this.libraryUrl + '/sections').pipe(map(data => data.items));
  }

  createLibrarySection(name: string) {
    return this.http.post<LibrarySection>(this.libraryUrl + '/sections', {name});
  }

  renameLibrarySection(id: string, name: string) {
    return this.http.patch<LibrarySection>(this.sectionUrl(id), {name});
  }

  deleteLibrarySection(id: string) {
    return this.http.delete<void>(this.sectionUrl(id));
  }

  addLibrarySectionEntries(id: string, entryIds: string[]) {
    return this.http.post<LibrarySection>(this.sectionUrl(id) + '/entries', {entryIds});
  }

  removeLibrarySectionEntry(id: string, entryId: string) {
    return this.http.delete<LibrarySection>(this.sectionUrl(id) + '/entries/' + encodeURIComponent(entryId));
  }

  private sectionUrl(id: string) {
    return this.libraryUrl + '/sections/' + encodeURIComponent(id);
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

  reading(key: TitleKey) {
    return this.http.get<ReadingState>(this.titleUrl(key) + '/reading');
  }

  claimReading(key: TitleKey, body: {eventId: string; sessionId: string; expectedRevision: string}) {
    return this.http.post<ReadingSession>(this.titleUrl(key) + '/reading/sessions', body, {keepalive: true});
  }

  saveReading(key: TitleKey, event: ReadingEvent) {
    return this.http.put<ReadingSaved>(this.titleUrl(key) + '/reading/progress', event, {keepalive: true});
  }

  markReading(key: TitleKey, chapter: string, language: string, isRead: boolean, expectedRevision: string) {
    return this.http.patch<ReadingSaved>(this.titleUrl(key) + '/chapters/' + encodeURIComponent(chapter) + '/reading',
      {eventId: crypto.randomUUID(), expectedRevision, isRead}, {params: {language}});
  }

  controlDownload(id: string,action: 'pause' | 'resume' | 'prioritize') {
    return this.http.post<Download>(this.downloadsUrl + '/' + id + '/queue/' + action,null);
  }

  bookmarks(key: TitleKey) {
    return this.http.get<ChapterBookmark[]>(this.titleUrl(key) + '/bookmarks');
  }

  chapterActions(key: TitleKey,action: ChapterAction,items: ChapterBookmark[]) {
    return this.http.post<ChapterActionResult[]>(this.titleUrl(key) + '/chapter-actions',{action,items});
  }

  backupExport() { return this.http.get<Record<string,unknown>>(this.base + '/library-backup'); }
  backupPreview(backup: Record<string,unknown>) {
    return this.http.post<BackupPreview>(this.base + '/library-backup/preview',backup);
  }
  backupRestore(backup: Record<string,unknown>) {
    return this.http.post<BackupPreview>(this.base + '/library-backup',backup);
  }
  transferPreview(entry:string,key:TitleKey) {
    return this.http.get<TransferPreview>(this.libraryUrl + '/' + entry + '/transfer',
      {params:{sourceId:key.sourceId,externalTitleId:key.externalTitleId}});
  }
  transfer(entry:string,key:TitleKey) {
    return this.http.post<{entryId:string;duplicate:boolean}>(this.libraryUrl + '/' + entry + '/transfer',
      {sourceId:key.sourceId,externalTitleId:key.externalTitleId,confirmed:true});
  }

  importLocal(body: FormData) {
    return this.http.post<LocalImportResult>(this.base + '/local-comics',body);
  }

  importServerFolder(body: {title:string;chapter:string;language:string;path:string}) {
    return this.http.post<LocalImportResult>(this.base + '/local-comics/folder',body);
  }

  libraryUpdates(language: string, before?: number) {
    let params = new HttpParams();
    if (language) params = params.set('language', language);
    if (before !== undefined) params = params.set('before', before);
    return this.http.get<{items: LibraryUpdateItem[]; unread: number; nextCursor: number | null}>(
      this.base + '/library-updates', {params});
  }

  readingHistory(cursor?: string) {
    return this.http.get<ReadingHistory>(this.base + '/reading-history', {params: cursor ? {cursor} : {}});
  }

  private titleUrl(key: TitleKey) {
    return this.sourceUrl(key.sourceId) + '/titles/' + encodeURIComponent(key.externalTitleId);
  }

  private sourceUrl(sourceId: string) {
    return this.base + '/sources/' + encodeURIComponent(sourceId);
  }
}
