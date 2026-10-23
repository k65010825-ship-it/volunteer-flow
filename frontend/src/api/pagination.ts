/** Backend pages preserve data arrays and carry paging details separately. */
export interface PageMeta {
  total: number | string;
  page: number | string;
  size: number | string;
}

export interface PageResult<T> {
  data: T[];
  meta: PageMeta;
}

export function normalizePage<T>(
  body: { data: T[]; meta?: PageMeta },
  page = 1,
  size = 20,
): PageResult<T> {
  return {
    data: body.data,
    meta: body.meta ?? { total: body.data.length, page, size },
  };
}
