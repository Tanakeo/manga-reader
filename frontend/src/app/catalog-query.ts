import { ParamMap } from '@angular/router';
import { CatalogQuery, Tag } from './api';

export const catalogSorts: Record<string, string> = {
  relevance: 'По релевантности',
  year_desc: 'Сначала новые по году',
  year_asc: 'Сначала старые по году',
  latest_chapter_desc: 'По последней добавленной главе',
};

export const tagLimit = 10;
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const sourcePattern = /^[a-z0-9][a-z0-9-]{0,63}$/;

export function normalizeCatalog(query: CatalogQuery): CatalogQuery {
  const sourceId = sourcePattern.test(query.sourceId) ? query.sourceId : '';
  const q = query.q.trim().slice(0, 120);
  const tags = query.tagIds.map(id => id.toLowerCase()).filter(id => uuid.test(id));
  return {
    sourceId,
    q,
    tagIds: sourceId ? [...new Set(tags)].slice(0, tagLimit).sort() : [],
    sort: sourceId && Object.hasOwn(catalogSorts, query.sort) && (query.sort !== 'relevance' || q) ? query.sort : '',
    chapterLanguages: [...new Set(query.chapterLanguages ?? [])].filter(language => ['ru', 'en'].includes(language)).sort(),
  };
}

export function readCatalog(params: ParamMap, sourceKey = 'sourceId') {
  return normalizeCatalog({
    sourceId: params.get(sourceKey) ?? '',
    q: params.get('q') ?? '',
    tagIds: params.getAll('tagId'),
    sort: params.get('sort') ?? '',
    chapterLanguages: params.getAll('chapterLanguage'),
  });
}

export function catalogParams(query: CatalogQuery, sourceKey = 'sourceId') {
  return {
    [sourceKey]: query.sourceId || null,
    q: query.q || null,
    tagId: query.tagIds.length ? query.tagIds : null,
    sort: query.sort || null,
    chapterLanguage: query.chapterLanguages?.length ? query.chapterLanguages : null,
  };
}

export function tagName(tag: Tag, language: string) {
  return tag.names[language] || tag.names['en'] ||
    Object.keys(tag.names).sort().map(key => tag.names[key]).find(Boolean) || tag.id;
}
