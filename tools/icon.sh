#!/usr/bin/env bash
#
# Пересобирает иконку приложения из исходного рисунка владельца.
#
#     bash tools/icon.sh tools/icon-source.webp
#
# Что тут происходит и почему именно так — в CLAUDE.md, раздел «Иконка».
# Коротко: слой адаптивной иконки 108dp, маска лаунчера гарантирует только
# центральные 72dp, рисунок садится кругом в 78dp и **заполняет его целиком** —
# ничего по краям не дорисовывается.
#
# Кадр сдвинут вправо-вверх на 4%: рисунок квадратный, флаг уходит в правый
# верхний угол, и круг по центру срезал бы у него треть. Сдвиг отдаёт левый край
# (там пустое небо) и спасает флаг — проверено под обеими масками, нашей в 78dp
# и гарантированной в 72dp.
#
# Pillow в проекте нет намеренно: ставить пакет в систему ради одной картинки
# незачем, а ffmpeg тут и так есть.
set -euo pipefail

SRC="${1:-tools/icon-source.webp}"
FF="${FFMPEG:-D:/Projects/_git/roop-unleashed/installer/installer_files/ffmpeg/bin/ffmpeg.exe}"
RES="src/app/src/main/res"
MASTER="$(mktemp -u).png"

# Круг разом в 1248px: каждая плотность уменьшается уже из него, и край
# остаётся гладким — сглаживание считается один раз и на большом размере.
# 1320 с обрезкой до 1248 со смещением (72,0) и есть тот самый сдвиг кадра.
"$FF" -hide_banner -loglevel error -y -i "$SRC" -vf \
  "scale=1320:1320,crop=1248:1248:72:0,format=rgba,\
geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lte(hypot(X-624,Y-624),623),255,0)'" \
  -frames:v 1 "$MASTER"

# плотность | слой, px | круг, px (78/108 от слоя)
for pair in "mdpi 108 78" "hdpi 162 117" "xhdpi 216 156" "xxhdpi 324 234" \
            "xxxhdpi 432 312"; do
  set -- $pair
  out="$RES/mipmap-$1/ic_launcher_foreground.png"
  off=$(( ($2 - $3) / 2 ))
  "$FF" -hide_banner -loglevel error -y -i "$MASTER" \
    -vf "scale=$3:$3,pad=$2:$2:$off:$off:color=black@0" -frames:v 1 "$out"
  printf '%-8s слой %3d, круг %3d — %s\n' "$1" "$2" "$3" "$out"
done

rm -f "$MASTER"
