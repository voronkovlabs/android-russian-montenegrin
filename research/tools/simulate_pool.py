# -*- coding: utf-8 -*-
"""
Чем оборачивается размер словарного пула.

Заведено 13.09.2026, когда владелец попросил держать в обороте «гораздо больше
чем 30, 300 например». Число выглядело безобидным, а симуляция показала обрыв:

    пул    слов начато   слов выучено   разрыв, дн.   долг
    30     49            19             6.2           0
    100    147           51             6.3           0
    150    211           61             6.2           0
    200    269           70             7.0           9
    300    305           7              10.3          59

До двухсот всё растёт вместе: и начатых слов больше, и выученных. На трёхстах
выученные обваливаются с семидесяти до семи. Причина не в разнообразии, а в
пропускной способности: двадцать пять карточек за заход не успевают за потоком
созревающих, карточки уходят в долг, забываются, сбрасываются в ноль
повторений — и десяти верных ответов не набирают никогда. Слова начинаются и не
доучиваются.

Поэтому `vocab.poolTarget` = 200, а не 300.

    PYTHONUTF8=1 python research/tools/simulate_pool.py

Модель грубая и намеренно оптимистичная: доля верных ответов взята постоянной
(75%), забывание между встречами не моделируется вовсе, заход ровно один в
день. Всё это завышает число выученных слов — значит настоящий обрыв не позже
смоделированного, а раньше.
"""

LIMIT = 25          # карточек за заход (vocab.sessionLimit)
LEARNED = 10        # верных ответов до «выучено»
FIRST, SECOND = 1, 3
EASE = 2.5
DAYS = 100
RECALL_HIT = 0.75   # доля верных ответов, взята оптимистично


class Card(object):
    __slots__ = ('word', 'due', 'interval', 'correct', 'reps')

    def __init__(self, word, day):
        self.word = word
        self.due = day
        self.interval = 0
        self.correct = 0
        self.reps = 0


def run(pool_target, words=3501, seed=1):
    import random
    rnd = random.Random(seed)
    cards = []
    by_word = {}
    next_word = 0
    seen_days = {}          # слово -> дни, когда встречалось

    for day in range(1, DAYS + 1):
        due = sorted([c for c in cards if c.due <= day], key=lambda c: c.due)

        rotation = len({c.word for c in cards if c.correct < LEARNED})
        room = max(0, pool_target - rotation)
        fresh = []
        while len(fresh) < LIMIT // 2 and room > 0 and next_word < words:
            c = Card(next_word, day)
            fresh.append(c)
            by_word.setdefault(next_word, []).append(c)
            next_word += 1
            room -= 1

        today = (fresh + due)[:LIMIT]
        for c in today:
            if c not in cards:
                cards.append(c)
            ok = c.reps == 0 or rnd.random() < RECALL_HIT
            if ok:
                c.reps += 1
                c.correct += 1
                if c.reps == 1:
                    c.interval = FIRST
                elif c.reps == 2:
                    c.interval = SECOND
                else:
                    c.interval = max(4, int(round(c.interval * EASE)))
                c.due = day + c.interval
            else:
                c.reps = 0
                c.interval = 0
                c.due = day + 1          # новое правило: назавтра, не через 10 минут
            seen_days.setdefault(c.word, []).append(day)

    learned = len({c.word for c in cards if c.correct >= LEARNED})
    started = len({c.word for c in cards})
    gaps = []
    for w, days in seen_days.items():
        gaps += [b - a for a, b in zip(days, days[1:])]
    overdue = len([c for c in cards if c.due <= DAYS])
    return {
        'pool': pool_target,
        'слов начато': started,
        'слов выучено': learned,
        'средний разрыв между встречами, дней':
            round(sum(gaps) / float(len(gaps)), 1) if gaps else 0,
        'просрочено к концу': overdue,
    }


print('Сто дней по одному заходу словаря (25 карточек), 75%% верных ответов\n')
head = ('пул', 'слов начато', 'слов выучено', 'разрыв, дн.', 'долг')
print('%-6s %-13s %-14s %-13s %s' % head)
for target in (30, 100, 300, 600):
    r = run(target)
    print('%-6d %-13d %-14d %-13s %d' % (
        r['pool'], r['слов начато'], r['слов выучено'],
        r['средний разрыв между встречами, дней'], r['просрочено к концу']))
