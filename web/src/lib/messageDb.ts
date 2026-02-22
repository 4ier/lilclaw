/**
 * Lightweight IndexedDB wrapper for offline message persistence.
 * Zero dependencies — promisified native IndexedDB API.
 */

import type { ChatMessage } from './gateway'

const DB_NAME = 'lilclaw-messages'
const DB_VERSION = 1
const STORE_NAME = 'messages'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME)
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

export async function saveMessages(sessionKey: string, messages: ChatMessage[]): Promise<void> {
  try {
    const db = await openDb()
    const tx = db.transaction(STORE_NAME, 'readwrite')
    tx.objectStore(STORE_NAME).put(messages, sessionKey)
    db.close()
  } catch {
    // Silent fail — offline cache is best-effort
  }
}

export async function loadMessages(sessionKey: string): Promise<ChatMessage[]> {
  try {
    const db = await openDb()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const req = tx.objectStore(STORE_NAME).get(sessionKey)
      req.onsuccess = () => {
        db.close()
        resolve(req.result || [])
      }
      req.onerror = () => {
        db.close()
        resolve([])
      }
    })
  } catch {
    return []
  }
}

export async function loadAllMessages(): Promise<Record<string, ChatMessage[]>> {
  try {
    const db = await openDb()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const result: Record<string, ChatMessage[]> = {}

      const cursorReq = store.openCursor()
      cursorReq.onsuccess = () => {
        const cursor = cursorReq.result
        if (cursor) {
          result[cursor.key as string] = cursor.value
          cursor.continue()
        } else {
          db.close()
          resolve(result)
        }
      }
      cursorReq.onerror = () => {
        db.close()
        resolve({})
      }
    })
  } catch {
    return {}
  }
}

export async function deleteSessionMessages(sessionKey: string): Promise<void> {
  try {
    const db = await openDb()
    const tx = db.transaction(STORE_NAME, 'readwrite')
    tx.objectStore(STORE_NAME).delete(sessionKey)
    db.close()
  } catch {
    // Silent fail
  }
}
