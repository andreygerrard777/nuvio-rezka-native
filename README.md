# Версія 6: прототип для фільмів

Перший ручний тест: **The Terminator / Термінатор (1984)**.
Відкрийте картку саме цього фільму в Nuvio та запустіть пошук джерел.
Назва пакета залишається Rezka Diagnostics для оновлення попередньої установки;
зареєстрований провайдер називається Rezka Native Movies.

Плагін зберігає cookie між пошуком, сторінкою та запитом відео. Повертає якості
першого озвучення з робочими URL, перевіряючи максимум три озвучення.
Українські озвучення мають пріоритет. Серіали та субтитри ще не підтримуються.
Пошук поважає запит Nuvio; автоматичний Test може перевіряти «Матрицю», а не «Термінатора».

Виявлені збої мають префікс REZKA_V6. Посилання не означає перевірене відтворення:
реальне відтворення й доступність перевіряються на Android TV.

## Попередні етапи

# nuvio-uk-providers

Unofficial Ukrainian scrapers for Nuvio.

## Credit

The logic for the providers in this repository is adapted from
[CakesTwix/cloudstream-extensions-uk](https://github.com/CakesTwix/cloudstream-extensions-uk).
All original research into how these sites work belongs to
[CakesTwix](https://github.com/CakesTwix). This repository would not exist without it.

## Usage

Add this repository's `manifest.json` raw URL as a plugin repository in Nuvio.
