## Normal reply

```yml
messages:
  - "Hello world, I'm a really smart assistant."
tool-calls: []
close-conversation: false
```

## Wiki tool call

```yml
messages: []
tool-calls:
  - "wiki commands"
close-conversation: false
```

## Natural ending

```yml
messages:
  - "You're welcome!"
tool-calls: []
close-conversation: true
```

## No response

```yml
messages: []
tool-calls: []
close-conversation: false
```
