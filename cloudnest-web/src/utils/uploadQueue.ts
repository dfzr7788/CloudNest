// Keep File/Blob data in IndexedDB: localStorage cannot preserve upload sources.
export interface SavedUpload {
  key: string;
  ownerId: string;
  name: string;
  size: number;
  folderId: string;
  segments?: string[];
  sessionId?: string;
  policy?: string;
  state: string;
  file?: File;
}

async function database() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("cloudnest-upload-queue", 1);
    request.onupgradeneeded = () => {
      const store = request.result.createObjectStore("tasks", {
        keyPath: "key",
      });
      store.createIndex("owner", "ownerId");
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function saveUploads(tasks: SavedUpload[], remove: string[] = []) {
  const db = await database();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("tasks", "readwrite");
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(tx.error || new Error("上传队列保存失败"));
      tx.onerror = () => reject(tx.error);
      const store = tx.objectStore("tasks");
      try {
        for (const key of remove) store.delete(key);
        for (const task of tasks) store.put(task);
      } catch (error) {
        tx.abort();
        reject(error);
      }
    });
  } finally {
    db.close();
  }
}

export async function loadUploads(ownerId: string): Promise<SavedUpload[]> {
  const db = await database();
  try {
    return await new Promise((resolve, reject) => {
      const request = db
        .transaction("tasks")
        .objectStore("tasks")
        .index("owner")
        .getAll(ownerId);
      request.onsuccess = () => resolve(request.result as SavedUpload[]);
      request.onerror = () => reject(request.error);
    });
  } finally {
    db.close();
  }
}
