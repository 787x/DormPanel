// 页面内对话框；消息逐个展示，不使用浏览器 alert/confirm/prompt。
let queue = Promise.resolve();
function showFeedback(kind, message, initialValue = '', acceptLabel = '') {
  const task = queue.then(() => new Promise(resolve => {
    const dialog = document.getElementById('feedback'),
      title = document.getElementById('feedbackTitle'),
      content = document.getElementById('feedbackMessage'),
      label = document.getElementById('feedbackInputLabel'),
      input = document.getElementById('feedbackInput'),
      cancel = document.getElementById('feedbackCancel'),
      accept = document.getElementById('feedbackAccept'),
      form = document.getElementById('feedbackForm');
    title.textContent = kind === 'text' ? '填写信息' : kind === 'confirm' ? '确认操作' : '提示';
    content.textContent = String(message);
    label.hidden = kind !== 'text';
    input.value = initialValue;
    cancel.hidden = kind === 'notice';
    accept.textContent = acceptLabel || (kind === 'notice' ? '知道了' : '确定');
    dialog.returnValue = 'cancel';
    cancel.onclick = () => dialog.close('cancel');
    form.onsubmit = event => { event.preventDefault(); dialog.close('accept'); };
    dialog.addEventListener('close', () => {
      const accepted = dialog.returnValue === 'accept', value = input.value;
      input.value = ''; content.textContent = '';
      cancel.onclick = null; form.onsubmit = null;
      resolve(kind === 'text' ? (accepted ? value : null) : accepted);
    }, { once: true });
    dialog.showModal();
    if (kind === 'text') { input.focus(); input.select(); }
    else (kind === 'confirm' ? cancel : accept).focus();
  }));
  queue = task.catch(() => {});
  return task;
}
export const notifyUser = message => showFeedback('notice', message);
export const confirmAction = (message, label = '确认操作') => showFeedback('confirm', message, '', label);
export const requestText = (message, value = '', label = '确定') => showFeedback('text', message, value, label);
