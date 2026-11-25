import { ReadingEvent, ReadingSample, TitleKey } from './api';

export interface ReadingBatch {
  sessionId: string;
  claimId: string;
  key: TitleKey;
  revision: string;
  claimed: boolean;
  sequence: number;
  sending?: ReadingEvent;
  pending: ReadingSample[];
  conflict?: string;
}

export class ReadingOutbox {
  private readonly database = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('kadr-reading', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('sessions');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

  constructor() { void this.database.catch(() => undefined); }

  async all(): Promise<ReadingBatch[]> {
    const db = await this.database;
    return new Promise((resolve, reject) => {
      const request = db.transaction('sessions').objectStore('sessions').getAll();
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  async write(batch: ReadingBatch, remove = false) {
    const db = await this.database;
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction('sessions', 'readwrite');
      const entries = transaction.objectStore('sessions');
      if (remove) entries.delete(batch.sessionId);
      else entries.put(batch, batch.sessionId);
      transaction.oncomplete = () => resolve();
      transaction.onabort = () => reject(transaction.error);
    });
  }

  async get(sessionId: string): Promise<ReadingBatch | undefined> {
    const db = await this.database;
    return new Promise((resolve, reject) => {
      const request = db.transaction('sessions').objectStore('sessions').get(sessionId);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
}
