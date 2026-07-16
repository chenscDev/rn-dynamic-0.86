module.exports = {
  preset: '@react-native/jest-preset',
  rootDir: require('path').resolve(__dirname, '..'),
  testMatch: ['<rootDir>/app/__tests__/**/*.[jt]s?(x)'],
};
