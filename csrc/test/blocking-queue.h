#ifndef BLOCKING_QUEUE_H
#define BLOCKING_QUEUE_H

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define DEFINE_BLOCKING_QUEUE(T, QUEUE_NAME) \
\
typedef struct QUEUE_NAME##_s { \
    T *data; \
    int capacity; \
    int front; \
    int rear; \
    int count; \
    pthread_mutex_t lock; \
    pthread_cond_t not_full; \
    pthread_cond_t not_empty; \
} QUEUE_NAME##_t; \
\
static inline int QUEUE_NAME##_init(QUEUE_NAME##_t *q, int capacity) { \
    if (capacity <= 0) return -1; \
    q->capacity = capacity; \
    q->front = 0; \
    q->rear = 0; \
    q->count = 0; \
    q->data = (T *)malloc(sizeof(T) * capacity); \
    if (!q->data) return -1; \
    if (pthread_mutex_init(&q->lock, NULL) != 0) { \
        free(q->data); \
        return -1; \
    } \
    if (pthread_cond_init(&q->not_full, NULL) != 0) { \
        pthread_mutex_destroy(&q->lock); \
        free(q->data); \
        return -1; \
    } \
    if (pthread_cond_init(&q->not_empty, NULL) != 0) { \
        pthread_mutex_destroy(&q->lock); \
        pthread_cond_destroy(&q->not_full); \
        free(q->data); \
        return -1; \
    } \
    return 0; \
} \
\
static inline void QUEUE_NAME##_destroy(QUEUE_NAME##_t *q) { \
    free(q->data); \
    pthread_mutex_destroy(&q->lock); \
    pthread_cond_destroy(&q->not_full); \
    pthread_cond_destroy(&q->not_empty); \
} \
\
static inline void QUEUE_NAME##_put(QUEUE_NAME##_t *q, T value) { \
    pthread_mutex_lock(&q->lock); \
    while (q->count == q->capacity) { \
        pthread_cond_wait(&q->not_full, &q->lock); \
    } \
    q->data[q->rear] = value; \
    q->rear = (q->rear + 1) % q->capacity; \
    q->count++; \
    pthread_cond_signal(&q->not_empty); \
    pthread_mutex_unlock(&q->lock); \
} \
\
static inline T QUEUE_NAME##_take(QUEUE_NAME##_t *q) { \
    pthread_mutex_lock(&q->lock); \
    while (q->count == 0) { \
        pthread_cond_wait(&q->not_empty, &q->lock); \
    } \
    T value = q->data[q->front]; \
    q->front = (q->front + 1) % q->capacity; \
    q->count--; \
    pthread_cond_signal(&q->not_full); \
    pthread_mutex_unlock(&q->lock); \
    return value; \
} \
\
static inline int QUEUE_NAME##_try_put(QUEUE_NAME##_t *q, T value) { \
    int result = 0; \
    pthread_mutex_lock(&q->lock); \
    if (q->count < q->capacity) { \
        q->data[q->rear] = value; \
        q->rear = (q->rear + 1) % q->capacity; \
        q->count++; \
        pthread_cond_signal(&q->not_empty); \
        result = 1; \
    } \
    pthread_mutex_unlock(&q->lock); \
    return result; \
} \
\
static inline int QUEUE_NAME##_try_take(QUEUE_NAME##_t *q, T *value) { \
    int result = 0; \
    pthread_mutex_lock(&q->lock); \
    if (q->count > 0) { \
        *value = q->data[q->front]; \
        q->front = (q->front + 1) % q->capacity; \
        q->count--; \
        pthread_cond_signal(&q->not_full); \
        result = 1; \
    } \
    pthread_mutex_unlock(&q->lock); \
    return result; \
} \
\
static inline int QUEUE_NAME##_size(QUEUE_NAME##_t *q) { \
    int size; \
    pthread_mutex_lock(&q->lock); \
    size = q->count; \
    pthread_mutex_unlock(&q->lock); \
    return size; \
} \
\
static inline int QUEUE_NAME##_is_empty(QUEUE_NAME##_t *q) { \
    int empty; \
    pthread_mutex_lock(&q->lock); \
    empty = (q->count == 0); \
    pthread_mutex_unlock(&q->lock); \
    return empty; \
} \
\
static inline int QUEUE_NAME##_is_full(QUEUE_NAME##_t *q) { \
    int full; \
    pthread_mutex_lock(&q->lock); \
    full = (q->count == q->capacity); \
    pthread_mutex_unlock(&q->lock); \
    return full; \
} \
\
static inline void QUEUE_NAME##_clear(QUEUE_NAME##_t *q) { \
    pthread_mutex_lock(&q->lock); \
    q->front = 0; \
    q->rear = 0; \
    q->count = 0; \
    pthread_cond_broadcast(&q->not_full); \
    pthread_mutex_unlock(&q->lock); \
}

#endif // BLOCKING_QUEUE_H